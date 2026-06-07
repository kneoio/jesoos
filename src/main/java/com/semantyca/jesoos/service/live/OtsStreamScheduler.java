package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.OneTimeStream;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.model.stream.TimelineEntryStatus;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class OtsStreamScheduler {
    private static final Logger LOGGER = Logger.getLogger(OtsStreamScheduler.class);

    private final Vertx vertx;
    private final AiAgentService aiAgentService;
    private final MetricPublisher metricPublisher;
    private final SongEmitter songEmitter;
    private final JingleSongEmitter jingleSongEmitter;
    private final GeneratedContentEmitter generatedContentEmitter;
    private final JesoosConfig config;

    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Long>> otsTimers = new ConcurrentHashMap<>();

    @Inject
    public OtsStreamScheduler(Vertx vertx,
                               AiAgentService aiAgentService,
                               MetricPublisher metricPublisher,
                               SongEmitter songEmitter,
                               JingleSongEmitter jingleSongEmitter,
                               GeneratedContentEmitter generatedContentEmitter,
                               JesoosConfig config) {
        this.vertx = vertx;
        this.aiAgentService = aiAgentService;
        this.metricPublisher = metricPublisher;
        this.songEmitter = songEmitter;
        this.jingleSongEmitter = jingleSongEmitter;
        this.generatedContentEmitter = generatedContentEmitter;
        this.config = config;
    }

    public void scheduleStream(OneTimeStream stream) {
        String otsSlugName = stream.getSlugName();
        String masterBrandSlug = stream.getMasterBrand().getSlugName();
        ZoneId zone = stream.getTimeZone();

        for (LiveScene scene : stream.getAgenda().getLiveScenes()) {
            scene.setOtsSlugName(otsSlugName);
            scheduleSceneSongs(otsSlugName, masterBrandSlug, scene, zone, stream);
        }
    }

    private void scheduleSceneSongs(String otsSlugName, String masterBrandSlug, LiveScene scene, ZoneId zone, IStream streamRef) {
        LocalDateTime now = LocalDateTime.now(zone);

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
            scheduleEntry(otsSlugName, masterBrandSlug, scene, entry, zone, streamRef);
        }
    }

    private void scheduleEntry(String otsSlugName, String masterBrandSlug, LiveScene scene, TimelineEntry entry, ZoneId zone, IStream streamRef) {
        if (!entry.compareAndSetStatus(TimelineEntryStatus.PENDING, TimelineEntryStatus.SCHEDULED)) {
            return;
        }

        long emissionTime = entry.getScheduledEmissionTime().atZone(zone).toInstant().toEpochMilli();
        long now = System.currentTimeMillis();
        long leadTimeMs = config.getAivoxDelaySeconds() * 1000L;
        long triggerTime = emissionTime - leadTimeMs;

        IStream capturedStream = streamRef;
        Runnable task = () -> {
            long deadline = scene.getEndTime().atZone(zone).toInstant().toEpochMilli();
            if (System.currentTimeMillis() >= deadline) {
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                return;
            }
            entry.setStatus(TimelineEntryStatus.EMITTING);
            emitEntry(masterBrandSlug, otsSlugName, scene, entry, zone, capturedStream)
                    .subscribe().with(
                            v -> entry.setStatus(TimelineEntryStatus.COMPLETED),
                            err -> {
                                entry.setStatus(TimelineEntryStatus.FAILED);
                                LOGGER.errorf(err, "OTS entry #%d FAILED for scene '%s' ots '%s'",
                                        entry.getSequenceNumber(), scene.getSceneTitle(), otsSlugName);
                                metricPublisher.publishMetric(
                                        otsSlugName,
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
                            }
                    );
        };

        if (triggerTime <= now) {
            removeTimer(otsSlugName, entry.getSequenceNumber());
            task.run();
            return;
        }

        long delay = triggerTime - now;
        long timerId = vertx.setTimer(delay, id -> {
            removeTimer(otsSlugName, entry.getSequenceNumber());
            task.run();
        });
        otsTimers.computeIfAbsent(otsSlugName, k -> new ConcurrentHashMap<>())
                .put(entry.getSequenceNumber(), timerId);
    }

    private Uni<Void> emitEntry(String masterBrandSlug, String otsSlugName, LiveScene scene, TimelineEntry entry, ZoneId zone, IStream stream) {
        LOGGER.infof("[OtsScheduler] emitting entry #%d scene '%s' ots '%s' via brand '%s'",
                entry.getSequenceNumber(), scene.getSceneTitle(), otsSlugName, masterBrandSlug);

        return aiAgentService.getById(scene.getAgentId())
                .chain(agent -> {
                    if (entry.isGenerated()) {
                        return generatedContentEmitter.send(masterBrandSlug, scene, entry, agent, stream, zone, StreamPriority.PRIORITIZED.getValue());
                    }
                    if (entry.isHasJingle()) {
                        return jingleSongEmitter.send(masterBrandSlug, scene, entry, agent, stream, zone, StreamPriority.PRIORITIZED.getValue());
                    }
                    return songEmitter.send(masterBrandSlug, scene, entry, agent, stream, zone, StreamPriority.PRIORITIZED.getValue());
                });
    }

    public void cancelOtsTimers(String otsSlugName) {
        ConcurrentHashMap<Integer, Long> timers = otsTimers.remove(otsSlugName);
        if (timers != null) {
            timers.values().forEach(vertx::cancelTimer);
        }
    }

    private void removeTimer(String otsSlugName, int sequenceNumber) {
        ConcurrentHashMap<Integer, Long> timers = otsTimers.get(otsSlugName);
        if (timers != null) {
            Long old = timers.remove(sequenceNumber);
            if (old != null) vertx.cancelTimer(old);
        }
    }

    @PreDestroy
    void cleanup() {
        otsTimers.forEach((slug, timers) -> timers.values().forEach(vertx::cancelTimer));
        otsTimers.clear();
    }
}
