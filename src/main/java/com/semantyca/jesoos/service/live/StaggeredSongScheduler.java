package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.model.stream.TimelineEntryStatus;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StaggeredSongScheduler {
    public static final int DEFAULT_JINGLE_DURATION = 10;

    private final Vertx vertx;
    private final AiAgentService aiAgentService;
    private final MetricPublisher metricPublisher;
    private final BrandPool brandPool;
    private final SongEmitter songEmitter;
    private final JingleSongEmitter jingleSongEmitter;
    private final GeneratedContentEmitter generatedContentEmitter;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Long>> brandTimers = new ConcurrentHashMap<>();
    private final JesoosConfig config;

    @Inject
    public StaggeredSongScheduler(Vertx vertx,
                                  BrandPool brandPool,
                                  AiAgentService aiAgentService,
                                  MetricPublisher metricPublisher,
                                  SongEmitter songEmitter,
                                  JingleSongEmitter jingleSongEmitter, GeneratedContentEmitter generatedContentEmitter,
                                  JesoosConfig config) {
        this.brandPool = brandPool;
        this.vertx = vertx;
        this.aiAgentService = aiAgentService;
        this.metricPublisher = metricPublisher;
        this.songEmitter = songEmitter;
        this.jingleSongEmitter = jingleSongEmitter;
        this.generatedContentEmitter = generatedContentEmitter;
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
        long delay = Math.max(1, emissionTime - leadTimeMs - now);

        Runnable task = () -> {
            long deadline = scene.getEndTime()
                    .atZone(brandZone)
                    .toInstant()
                    .toEpochMilli();

            if (System.currentTimeMillis() >= deadline) {
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                return;
            }

            entry.setStatus(TimelineEntryStatus.EMITTING);

            emitTimelineEntry(brandName, scene, entry, brandZone)
                    .subscribe().with(
                            v -> {
                                entry.setStatus(TimelineEntryStatus.COMPLETED);
                            },
                            err -> {
                                entry.setStatus(TimelineEntryStatus.FAILED);
                                String errorMsg = err.getMessage();
                                if (errorMsg == null) {
                                    errorMsg = err.getClass().getSimpleName();
                                }
                                metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "entry_failed",
                                        Map.of(
                                                "seq", entry.getSequenceNumber(),
                                                "scene", scene.getSceneTitle(),
                                                "error", errorMsg),
                                        scene.getTraceId());
                            }
                    );
        };

        long timerId = vertx.setTimer(delay, id -> {
            ConcurrentHashMap<Integer, Long> timers = brandTimers.get(brandName);
            if (timers != null) {
                timers.remove(entry.getSequenceNumber());
            }
            task.run();
        });
        brandTimers.computeIfAbsent(brandName, k -> new ConcurrentHashMap<>())
                .put(entry.getSequenceNumber(), timerId);

        return true;
    }

    public Uni<Void> emitTimelineEntry(String brandName, LiveScene liveScene, TimelineEntry entry, ZoneId brandZone) {
        return brandPool.get(brandName)
                .chain(stream -> {

                    if (entry.isGenerated()) {
                        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                .chain(mainAgent -> {
                                    Uni<AiAgent> agentUni =
                                            (mainAgent.getCopilot() != null)
                                                    ? aiAgentService.getById(mainAgent.getCopilot(), SuperUser.build(), LanguageCode.en)
                                                    : Uni.createFrom().item(mainAgent);
                                    return agentUni.chain(agent ->
                                            generatedContentEmitter.send(brandName, liveScene, entry, agent, stream, brandZone));
                                });
                    }

                    if (entry.isHasJingle()) {
                        return jingleSongEmitter.send(brandName, liveScene, entry, stream, brandZone);
                    }

                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            .chain(agent -> songEmitter.send(brandName, liveScene, entry, agent, stream, brandZone));
                });
    }


    public void cancelBrandTimers(String brandName) {
        ConcurrentHashMap<Integer, Long> timers = brandTimers.remove(brandName);
        if (timers != null) {
            timers.values().forEach(vertx::cancelTimer);
        }
    }

    public void publishSceneSummary(String brandName, LiveScene scene) {
        List<String> summary = scene.getTimeline().stream()
                .map(e -> "#" + e.getSequenceNumber() + "@" + TimeFormatUtil.formatTime(e.getScheduledEmissionTime()) + "[" + e.getStatus() + "]")
                .toList();
        metricPublisher.publishMetric(
                brandName,
                MetricEventType.INFORMATION,
                ProcessType.FLOW,
                "scene_summary",
                Map.of(
                        "scene", scene.getSceneTitle(),
                        "entries", summary
                ),
                scene.getTraceId()
        );
    }

    @PreDestroy
    void cleanup() {
        brandTimers.forEach((brand, timers) -> {
            timers.values().forEach(vertx::cancelTimer);
        });
        brandTimers.clear();
    }
}