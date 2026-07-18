package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.messaging.CommandPublisher;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.OneTimeStream;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.model.stream.TimelineEntryStatus;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.mixpla.dto.queue.command.CommandType;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
import com.semantyca.jesoos.model.stream.ILiveStream;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class OtsStreamScheduler {
    private static final Logger LOGGER = Logger.getLogger(OtsStreamScheduler.class);

    private final Vertx vertx;
    private final AiAgentService aiAgentService;
    private final MetricPublisher metricPublisher;
    private final CommandPublisher commandPublisher;
    private final SongEmitter songEmitter;
    private final GeneratedContentEmitter generatedContentEmitter;
    private final JesoosConfig config;
    private final OneTimeStreamPool pool;

    // Field-injected to avoid a constructor cycle (ChatService transitively wires ChatAgent).
    @Inject
    com.semantyca.jesoos.service.chat.ChatService chatService;

    // Inner key is "sceneId:sequenceNumber" — sequenceNumber alone is only unique within a scene,
    // and OTS schedules every scene's entries up front in one pass, so scene A's entry #0 and
    // scene B's entry #0 would otherwise collide and one could cancel the other's real timer.
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> otsTimers = new ConcurrentHashMap<>();
    private final Set<String> finishedStreams = ConcurrentHashMap.newKeySet();

    @Inject
    public OtsStreamScheduler(Vertx vertx,
                               AiAgentService aiAgentService,
                               MetricPublisher metricPublisher,
                               CommandPublisher commandPublisher,
                               SongEmitter songEmitter,
                               GeneratedContentEmitter generatedContentEmitter,
                               JesoosConfig config,
                               OneTimeStreamPool pool) {
        this.vertx = vertx;
        this.aiAgentService = aiAgentService;
        this.metricPublisher = metricPublisher;
        this.commandPublisher = commandPublisher;
        this.songEmitter = songEmitter;
        this.generatedContentEmitter = generatedContentEmitter;
        this.config = config;
        this.pool = pool;
    }

    public void scheduleStream(OneTimeStream stream) {
        // Routing/tag identity is always the OTS's own slug — aivox's LiveStreamPool keys the
        // station on it directly (initializeOtsStation(slug, ...)), brand-scoped or not. The
        // master brand (if any) only feeds song-sourcing/codec defaults, never DJ status: a
        // personal one-time stream always talks, regardless of the master brand's live toggle.
        String streamSlug = stream.getSlugName();
        ZoneId zone = stream.getTimeZone();

        for (LiveScene scene : stream.getAgenda().getLiveScenes()) {
            scene.setOtsSlugName(streamSlug);
            scheduleSceneSongs(streamSlug, scene, zone, stream);
        }
    }

    private void scheduleSceneSongs(String streamSlug, LiveScene scene, ZoneId zone, ILiveStream streamRef) {
        LocalDateTime now = LocalDateTime.now(zone);
        List<String> scheduledTimes = new ArrayList<>();

        for (TimelineEntry entry : scene.getTimeline()) {
            if (entry.getStatus() != TimelineEntryStatus.PENDING) continue;
            if (entry.getScheduledEmissionTime().isBefore(now)) {
                LocalDateTime entryEnd = entry.getScheduledEmissionTime()
                        .plusSeconds(entry.getEstimatedDurationSeconds());
                if (entryEnd.isBefore(now)) {
                    entry.setStatus(TimelineEntryStatus.SKIPPED);
                    continue;
                }
            }
            scheduleEntry(streamSlug, scene, entry, zone, streamRef);
            scheduledTimes.add("#" + entry.getSequenceNumber() + "@" + entry.getScheduledEmissionTime().toLocalTime() + "[" + entry.getStatus() + "]");
        }

        if (!scheduledTimes.isEmpty()) {
            metricPublisher.publishMetric(
                    streamSlug,
                    MetricEventType.INFORMATION,
                    ProcessType.FLOW,
                    "entries_scheduled",
                    Map.of(
                            "scene", scene.getSceneTitle(),
                            "entries", scheduledTimes,
                            "currentTime", TimeFormatUtil.formatTime(now)
                    ),
                    scene.getTraceId()
            );
        }
    }

    private void scheduleEntry(String streamSlug, LiveScene scene, TimelineEntry entry, ZoneId zone, ILiveStream capturedStream) {
        if (!entry.compareAndSetStatus(TimelineEntryStatus.PENDING, TimelineEntryStatus.SCHEDULED)) {
            return;
        }

        long emissionTime = entry.getScheduledEmissionTime().atZone(zone).toInstant().toEpochMilli();
        long now = System.currentTimeMillis();
        long leadTimeMs = config.getAivoxDelaySeconds() * 1000L;
        long triggerTime = emissionTime - leadTimeMs;

        Runnable task = () -> {
            long deadline = scene.getEndTime().atZone(zone).toInstant().toEpochMilli();
            if (System.currentTimeMillis() >= deadline) {
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                checkOtsFinished(streamSlug, capturedStream);
                return;
            }
            entry.setStatus(TimelineEntryStatus.EMITTING);
            UUID emissionTraceId = UUID.randomUUID();
            emitEntry(streamSlug, scene, entry, zone, capturedStream, emissionTraceId)
                    .subscribe().with(
                            v -> {
                                entry.setStatus(TimelineEntryStatus.COMPLETED);
                                checkOtsFinished(streamSlug, capturedStream);
                            },
                            err -> {
                                entry.setStatus(TimelineEntryStatus.FAILED);
                                LOGGER.errorf(err, "OTS entry #%d FAILED for scene '%s' ots '%s'",
                                        entry.getSequenceNumber(), scene.getSceneTitle(), streamSlug);
                                metricPublisher.publishMetric(
                                        streamSlug,
                                        MetricEventType.ERROR,
                                        ProcessType.FLOW,
                                        "ots_entry_failed",
                                        Map.of(
                                                "seq", entry.getSequenceNumber(),
                                                "scene", scene.getSceneTitle(),
                                                "error", err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName()
                                        ),
                                        scene.getTraceId()
                                );
                                checkOtsFinished(streamSlug, capturedStream);
                            }
                    );
        };

        String timerKey = timerKey(scene, entry);

        if (triggerTime <= now) {
            removeTimer(streamSlug, timerKey);
            task.run();
            return;
        }

        long delay = triggerTime - now;
        long timerId = vertx.setTimer(delay, id -> {
            removeTimer(streamSlug, timerKey);
            task.run();
        });
        otsTimers.computeIfAbsent(streamSlug, k -> new ConcurrentHashMap<>())
                .put(timerKey, timerId);
    }

    private static String timerKey(LiveScene scene, TimelineEntry entry) {
        return scene.getSceneId() + ":" + entry.getSequenceNumber();
    }

    private Uni<Void> emitEntry(String streamSlug, LiveScene scene, TimelineEntry entry, ZoneId zone, ILiveStream stream, UUID emissionTraceId) {
        LOGGER.infof("[OtsScheduler] emitting entry #%d scene '%s' stream '%s'",
                entry.getSequenceNumber(), scene.getSceneTitle(), streamSlug);

        return aiAgentService.getById(scene.getAgentId())
                .chain(agent -> {
                    if (entry.isGenerated()) {
                        return generatedContentEmitter.send(streamSlug, scene, entry, agent, stream, zone, StreamPriority.PRIORITIZED_FRONT.getValue(), emissionTraceId);
                    }
                    return songEmitter.send(streamSlug, true, scene, entry, agent, stream, zone, StreamPriority.PRIORITIZED.getValue(), emissionTraceId);
                });
    }

    private void checkOtsFinished(String streamSlug, ILiveStream stream) {
        if (finishedStreams.contains(streamSlug)) {
            return;
        }
        List<LiveScene> scenes = stream.getAgenda().getLiveScenes();
        if (scenes.isEmpty() || !scenes.stream().allMatch(LiveScene::isFinished)) {
            return;
        }
        if (!finishedStreams.add(streamSlug)) {
            return;
        }

        long plannedDeadline = scenes.stream()
                .mapToLong(scene -> scene.getEndTime().atZone(scene.getTimeZone()).toInstant().toEpochMilli())
                .max()
                .orElse(System.currentTimeMillis());

        // The planned deadline budgets a flat allowance per intro, but real TTS length varies
        // (8s..36s observed against a 10s budget), so tearing down on the plan cuts the final
        // entry mid-song — the last scene has nothing after it to absorb the overrun.
        // trackEmission recorded the *actual* song + intro seconds at emit time, and emission runs
        // aivox-delay-seconds ahead of playback, so playout ends that much after the tracked
        // instant. Never finish earlier than planned; extend when reality ran long.
        Instant trackedEnd = metricPublisher.expectedContentEndAt(streamSlug);
        long deadline = trackedEnd == null
                ? plannedDeadline
                : Math.max(plannedDeadline,
                        trackedEnd.plusSeconds(config.getAivoxDelaySeconds()).toEpochMilli());

        long delay = deadline - System.currentTimeMillis();

        if (delay > 0) {
            vertx.setTimer(delay, id -> finishOts(streamSlug, scenes.size()));
        } else {
            finishOts(streamSlug, scenes.size());
        }
    }

    private void finishOts(String streamSlug, int sceneCount) {
        LOGGER.infof("[OtsScheduler] OTS finished: slugName=%s", streamSlug);
        UUID traceId = UUID.randomUUID();
        metricPublisher.publishMetric(
                streamSlug,
                MetricEventType.INFORMATION,
                ProcessType.FLOW,
                "ots_finished",
                Map.of("scenes", sceneCount),
                traceId
        );
        commandPublisher.publishCommand(
                CommandType.JESOOS_OTS_FINISHED,
                "ots_finished",
                Map.of("streamSlug", streamSlug),
                traceId
        );
        cancelOtsTimers(streamSlug);
        chatService.purgeOtsChat(streamSlug);
        pool.stopAndRemove(streamSlug).subscribe().with(
                v -> metricPublisher.clearTracking(streamSlug),
                err -> LOGGER.errorf(err, "Failed to stop/remove finished OTS '%s'", streamSlug)
        );
    }

    public void cancelOtsTimers(String otsSlugName) {
        ConcurrentHashMap<String, Long> timers = otsTimers.remove(otsSlugName);
        if (timers != null) {
            timers.values().forEach(vertx::cancelTimer);
        }
    }

    private void removeTimer(String otsSlugName, String timerKey) {
        ConcurrentHashMap<String, Long> timers = otsTimers.get(otsSlugName);
        if (timers != null) {
            Long old = timers.remove(timerKey);
            if (old != null) vertx.cancelTimer(old);
        }
    }

    @PreDestroy
    void cleanup() {
        otsTimers.forEach((slug, timers) -> timers.values().forEach(vertx::cancelTimer));
        otsTimers.clear();
    }
}
