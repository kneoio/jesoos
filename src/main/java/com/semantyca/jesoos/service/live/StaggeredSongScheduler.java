package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.model.stream.TimelineEntryStatus;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StaggeredSongScheduler {
    private static final Logger LOGGER = Logger.getLogger(StaggeredSongScheduler.class);
    public static final int DEFAULT_JINGLE_DURATION = 10;

    private final Vertx vertx;
    private final AiAgentService aiAgentService;
    private final MetricPublisher metricPublisher;
    private final BrandPool brandPool;
    private final SongEmitter songEmitter;
    private final JingleSongEmitter jingleSongEmitter;
    private final GeneratedContentEmitter generatedContentEmitter;
    private final DjStateService djStateService;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Long>> brandTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> skipCounters = new ConcurrentHashMap<>();
    private final JesoosConfig config;

    @Inject
    public StaggeredSongScheduler(Vertx vertx,
                                  BrandPool brandPool,
                                  AiAgentService aiAgentService,
                                  MetricPublisher metricPublisher,
                                  SongEmitter songEmitter,
                                  JingleSongEmitter jingleSongEmitter, GeneratedContentEmitter generatedContentEmitter,
                                  DjStateService djStateService,
                                  JesoosConfig config) {
        this.brandPool = brandPool;
        this.vertx = vertx;
        this.aiAgentService = aiAgentService;
        this.metricPublisher = metricPublisher;
        this.songEmitter = songEmitter;
        this.jingleSongEmitter = jingleSongEmitter;
        this.generatedContentEmitter = generatedContentEmitter;
        this.djStateService = djStateService;
        this.config = config;
    }

    public void scheduleSceneSongs(String brandName, LiveScene scene) {
        LocalDateTime now = LocalDateTime.now(scene.getTimeZone());
        List<String> scheduledTimes = new ArrayList<>();

        for (TimelineEntry entry : scene.getTimeline()) {
            if (entry.getStatus() == TimelineEntryStatus.SCHEDULED) {
                ConcurrentHashMap<Integer, Long> timers = brandTimers.get(brandName);
                if (timers == null || !timers.containsKey(entry.getSequenceNumber())) {
                    entry.setStatus(TimelineEntryStatus.PENDING);
                }
            }
            if (entry.getStatus() != TimelineEntryStatus.PENDING) {
                continue;
            }
            if (entry.getScheduledEmissionTime().isBefore(now)) {
                LocalDateTime entryEnd = entry.getScheduledEmissionTime()
                        .plusSeconds(entry.getEstimatedDurationSeconds());
                if (entryEnd.isBefore(now)) {
                    entry.setStatus(TimelineEntryStatus.SKIPPED);
                    continue;
                }
            }
            if (scheduleTimelineEntry(brandName, scene, entry, scene.getTimeZone())) {
                scheduledTimes.add("#" + entry.getSequenceNumber() + "@" + entry.getScheduledEmissionTime().toLocalTime() + "[" + entry.getStatus() + "]");
            }
        }

        if (!scheduledTimes.isEmpty()) {
            metricPublisher.publishMetric(
                    brandName,
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

    private boolean scheduleTimelineEntry(String brandName, LiveScene scene, TimelineEntry entry, ZoneId brandZone) {
        if (!entry.compareAndSetStatus(TimelineEntryStatus.PENDING, TimelineEntryStatus.SCHEDULED)) {
            return false;
        }

        long emissionTime = entry.getScheduledEmissionTime()
                .atZone(brandZone)
                .toInstant()
                .toEpochMilli();

        long now = System.currentTimeMillis();
        long leadTimeMs = config.getAivoxDelaySeconds() * 1000L;
        long triggerTime = emissionTime - leadTimeMs;

        Runnable task = () -> {
            long deadline = scene.getEndTime()
                    .atZone(brandZone)
                    .toInstant()
                    .toEpochMilli();

            if (System.currentTimeMillis() >= deadline) {
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                return;
            }

            java.util.concurrent.atomic.AtomicInteger skipCounter = skipCounters.get(brandName);
            if (skipCounter != null && skipCounter.get() > 0 && skipCounter.decrementAndGet() >= 0) {
                LOGGER.infof("Backpressure skip: entry #%d for brand '%s' skipped (%d remaining)",
                        entry.getSequenceNumber(), brandName, skipCounter.get());
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                return;
            }

            entry.setStatus(TimelineEntryStatus.EMITTING);

            emitTimelineEntry(brandName, scene, entry, brandZone)
                    .subscribe().with(
                            v -> entry.setStatus(TimelineEntryStatus.COMPLETED),
                            err -> {
                                entry.setStatus(TimelineEntryStatus.FAILED);
                                String errorMsg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
                                Throwable rootCause = rootCause(err);
                                String rootMsg = rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
                                LOGGER.error(String.format(
                                        "Entry #%d FAILED for scene '%s' brand '%s' → %s: %s (root: %s: %s)",
                                        entry.getSequenceNumber(), scene.getSceneTitle(), brandName,
                                        err.getClass().getSimpleName(), errorMsg,
                                        rootCause.getClass().getSimpleName(), rootMsg
                                ), err);
                                StackTraceElement[] frames = rootCause.getStackTrace();
                                StringBuilder stackSnippet = new StringBuilder();
                                for (int i = 0; i < Math.min(5, frames.length); i++) {
                                    stackSnippet.append(frames[i].toString()).append("\n");
                                }
                                metricPublisher.publishMetric(
                                        brandName,
                                        MetricEventType.ERROR,
                                        ProcessType.FLOW,
                                        "entry_failed",
                                        Map.of(
                                                "seq", entry.getSequenceNumber(),
                                                "scene", scene.getSceneTitle(),
                                                "errorType", err.getClass().getSimpleName(),
                                                "error", errorMsg,
                                                "rootCause", rootCause.getClass().getSimpleName() + ": " + rootMsg,
                                                "stackTrace", stackSnippet.toString().trim()
                                        ),
                                        scene.getTraceId()
                                );
                                triggerNextEntry(brandName, scene, entry, brandZone);
                            }
                    );
        };

        if (triggerTime <= now) {
            ConcurrentHashMap<Integer, Long> timers = brandTimers.get(brandName);
            if (timers != null) {
                Long old = timers.remove(entry.getSequenceNumber());
                if (old != null) {
                    vertx.cancelTimer(old);
                }
            }
            task.run();
            return true;
        }

        long delay = triggerTime - now;

        long timerId = vertx.setTimer(delay, id -> {
            ConcurrentHashMap<Integer, Long> timers = brandTimers.get(brandName);
            if (timers != null) {
                timers.remove(entry.getSequenceNumber());
            }
            task.run();
        });

        ConcurrentHashMap<Integer, Long> timers =
                brandTimers.computeIfAbsent(brandName, k -> new ConcurrentHashMap<>());

        Long old = timers.put(entry.getSequenceNumber(), timerId);
        if (old != null) {
            vertx.cancelTimer(old);
        }

        return true;
    }

    public Uni<Void> emitTimelineEntry(String brandName, LiveScene liveScene, TimelineEntry entry, ZoneId brandZone) {
        StreamPriority songPriority = djStateService.isDjEnabled(brandName) ? StreamPriority.PRIORITIZED : StreamPriority.NORMAL;
        return emitTimelineEntry(brandName, liveScene, entry, brandZone, entry.isGenerated() ? StreamPriority.PRIORITIZED_FRONT.getValue() : songPriority.getValue());
    }

    public Uni<Void> emitTimelineEntry(String brandName, LiveScene liveScene, TimelineEntry entry, ZoneId brandZone, int priority) {
        LOGGER.infof("Emitting entry #%d for scene '%s' brand '%s' (generated=%s, jingle=%s)",
                entry.getSequenceNumber(), liveScene.getSceneTitle(), brandName,
                entry.isGenerated(), entry.isHasJingle());
        return brandPool.get(brandName)
                .chain(stream -> {

                    if (entry.isGenerated()) {
                        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build())
                                .chain(mainAgent ->
                                        generatedContentEmitter.send(brandName, liveScene, entry, mainAgent, stream, brandZone, priority))
                                .onFailure().invoke(err -> LOGGER.error(String.format(
                                        "Generated content emitter failed for entry #%d scene '%s': %s",
                                        entry.getSequenceNumber(), liveScene.getSceneTitle(), err.getMessage()), err));
                    }

                    if (entry.isHasJingle()) {
                        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build())
                                .chain(agent -> jingleSongEmitter.send(brandName, liveScene, entry, agent, stream, brandZone, priority))
                                .onFailure().invoke(err -> LOGGER.error(String.format(
                                        "Jingle emitter failed for entry #%d scene '%s': %s",
                                        entry.getSequenceNumber(), liveScene.getSceneTitle(), err.getMessage()), err));
                    }

                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build())
                            .chain(agent -> songEmitter.send(brandName, liveScene, entry, agent, stream, brandZone, priority))
                            .onFailure().invoke(err -> LOGGER.error(String.format(
                                    "Song emitter failed for entry #%d scene '%s': %s",
                                    entry.getSequenceNumber(), liveScene.getSceneTitle(), err.getMessage()), err));
                });
    }

    public int backpressure(String brandName) {
        int pending = skipCounters.computeIfAbsent(brandName, k -> new java.util.concurrent.atomic.AtomicInteger(0))
                .incrementAndGet();
        LOGGER.infof("Backpressure signal received for brand '%s': %d skip(s) queued", brandName, pending);
        return pending;
    }

    public void cancelBrandTimers(String brandName) {
        ConcurrentHashMap<Integer, Long> timers = brandTimers.remove(brandName);
        if (timers != null) {
            timers.values().forEach(vertx::cancelTimer);
        }
    }

    private void triggerNextEntry(String brandName, LiveScene scene, TimelineEntry failed, ZoneId brandZone) {
        List<TimelineEntry> timeline = scene.getTimeline();
        if (timeline == null) return;
        int nextSeq = failed.getSequenceNumber() + 1;
        timeline.stream()
                .filter(e -> e.getSequenceNumber() == nextSeq && e.getStatus() == TimelineEntryStatus.SCHEDULED)
                .findFirst()
                .ifPresent(next -> {
                    ConcurrentHashMap<Integer, Long> timers = brandTimers.get(brandName);
                    if (timers != null) {
                        Long timerId = timers.remove(next.getSequenceNumber());
                        if (timerId != null) {
                            vertx.cancelTimer(timerId);
                        }
                    }
                    LOGGER.infof("Entry #%d failed — triggering next entry #%d immediately for brand '%s'",
                            failed.getSequenceNumber(), next.getSequenceNumber(), brandName);
                    next.setStatus(TimelineEntryStatus.EMITTING);
                    emitTimelineEntry(brandName, scene, next, brandZone)
                            .subscribe().with(
                                    v -> next.setStatus(TimelineEntryStatus.COMPLETED),
                                    err -> {
                                        next.setStatus(TimelineEntryStatus.FAILED);
                                        LOGGER.errorf("Cascade failure: entry #%d also failed for brand '%s'", next.getSequenceNumber(), brandName);
                                        metricPublisher.publishMetric(
                                                brandName,
                                                MetricEventType.FATAL_ERROR,
                                                ProcessType.FLOW,
                                                "cascade_entry_failed",
                                                Map.of(
                                                        "failedSeq", failed.getSequenceNumber(),
                                                        "cascadeSeq", next.getSequenceNumber(),
                                                        "scene", scene.getSceneTitle(),
                                                        "error", err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName()
                                                ),
                                                scene.getTraceId()
                                        );
                                    }
                            );
                });
    }

    private Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    @PreDestroy
    void cleanup() {
        brandTimers.forEach((brand, timers) -> {
            timers.values().forEach(vertx::cancelTimer);
        });
        brandTimers.clear();
    }
}