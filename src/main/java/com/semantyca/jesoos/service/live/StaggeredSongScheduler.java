package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.cnst.BoostType;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.model.stream.TimelineEntryStatus;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.mixpla.model.ScenePrompt;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class StaggeredSongScheduler {
    private static final Logger LOGGER = Logger.getLogger(StaggeredSongScheduler.class);

    private final Vertx vertx;
    private final AiAgentService aiAgentService;
    private final MetricPublisher metricPublisher;
    private final BrandPool brandPool;
    private final SongEmitter songEmitter;
    private final JingleSongEmitter jingleSongEmitter;
    private final GeneratedContentEmitter generatedContentEmitter;
    private final DjStateService djStateService;
    private static final Random RANDOM = new Random();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Long>> brandTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> skipCounters = new ConcurrentHashMap<>();
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
        int consecutiveBoostIntroCount = 0;

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
            boolean hasActiveIntroPrompts = scene.getIntroPrompts() != null
                    && scene.getIntroPrompts().stream().anyMatch(ScenePrompt::isActive);
            if (!entry.isHasIntro() && consecutiveBoostIntroCount < 2 && hasActiveIntroPrompts && djStateService.isDjEnabled(brandName)) {
                BoostType boostType = djStateService.consumeBoostEntry(brandName);
                if (boostType != null) {
                    entry.setHasIntro(true);
                    entry.setBoostType(boostType);
                    MixingType strategy = entry.getMixingStrategy();
                    if (boostType == BoostType.JINGLE_INTRO) {
                        entry.setHasJingle(true);
                        if (strategy != MixingType.JINGLE_INTRO_SONG) {
                            entry.setMixingStrategy(MixingType.JINGLE_INTRO_SONG);
                        }
                    } else {
                        if (strategy == MixingType.SONG_ONLY
                                || strategy == MixingType.SONG_CROSSFADE_SONG
                                || strategy == MixingType.SONG_CROSSFADE_SONG_VAR_1
                                || strategy == MixingType.FILLER_JINGLE) {
                            entry.setMixingStrategy(entry.getSongs().size() >= 2
                                    ? MixingType.SONG_INTRO_SONG
                                    : MixingType.INTRO_SONG);
                        }
                    }
                    assignBoostPrompt(entry, scene);
                    LOGGER.infof("DJ boost (%s): forced intro on entry #%d for brand '%s' (strategy: %s)",
                            boostType, entry.getSequenceNumber(), brandName, entry.getMixingStrategy());
                    metricPublisher.publishMetric(
                            brandName,
                            MetricEventType.WARNING,
                            ProcessType.FLOW,
                            "dj_boost_applied",
                            Map.of(
                                    "boostType", boostType.name(),
                                    "entry", entry.getSequenceNumber(),
                                    "strategy", entry.getMixingStrategy().name()
                            ),
                            scene.getTraceId()
                    );
                }
            }
            consecutiveBoostIntroCount = entry.isHasIntro() ? consecutiveBoostIntroCount + 1 : 0;
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

            AtomicInteger skipCounter = skipCounters.get(brandName);
            if (skipCounter != null && skipCounter.get() > 0 && skipCounter.decrementAndGet() >= 0) {
                LOGGER.infof("Backpressure skip: entry #%d for brand '%s' skipped (%d remaining)",
                        entry.getSequenceNumber(), brandName, skipCounter.get());
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                return;
            }

            entry.setStatus(TimelineEntryStatus.EMITTING);

            UUID emissionTraceId = UUID.randomUUID();
            metricPublisher.publishMetric(
                    brandName,
                    MetricEventType.INFORMATION,
                    ProcessType.FLOW,
                    "entry_emitting_started",
                    Map.of(
                            "seq", entry.getSequenceNumber(),
                            "scene", scene.getSceneTitle()
                    ),
                    emissionTraceId
            );

            boolean hasActiveIntroPrompts = scene.getIntroPrompts() != null
                    && scene.getIntroPrompts().stream().anyMatch(ScenePrompt::isActive);
            if (!entry.isHasIntro() && hasActiveIntroPrompts && djStateService.isDjEnabled(brandName)) {
                BoostType boostType = djStateService.consumeBoostEntry(brandName);
                if (boostType != null) {
                    entry.setHasIntro(true);
                    entry.setBoostType(boostType);
                    MixingType strategy = entry.getMixingStrategy();
                    if (boostType == BoostType.JINGLE_INTRO) {
                        entry.setHasJingle(true);
                        if (strategy != MixingType.JINGLE_INTRO_SONG) {
                            entry.setMixingStrategy(MixingType.JINGLE_INTRO_SONG);
                        }
                    } else {
                        if (strategy == MixingType.SONG_ONLY
                                || strategy == MixingType.SONG_CROSSFADE_SONG
                                || strategy == MixingType.SONG_CROSSFADE_SONG_VAR_1
                                || strategy == MixingType.FILLER_JINGLE) {
                            entry.setMixingStrategy(entry.getSongs().size() >= 2
                                    ? MixingType.SONG_INTRO_SONG
                                    : MixingType.INTRO_SONG);
                        }
                    }
                    assignBoostPrompt(entry, scene);
                    metricPublisher.publishMetric(
                            brandName, MetricEventType.WARNING, ProcessType.FLOW,
                            "dj_boost_applied",
                            Map.of("boostType", boostType.name(), "entry", entry.getSequenceNumber(),
                                    "strategy", entry.getMixingStrategy().name()),
                            emissionTraceId
                    );
                }
            }

            emitTimelineEntry(brandName, scene, entry, brandZone, emissionTraceId)
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
                                                "song", entry.getSongs().stream().map(s -> s.getSoundFragment().getTitle() + " – " + s.getSoundFragment().getArtist()).collect(Collectors.joining(", ")),
                                                "promptId", entry.getSongs().stream().map(s -> String.valueOf(s.getPromptEntry().getPromptId())).collect(Collectors.joining(", ")),
                                                "errorType", err.getClass().getSimpleName(),
                                                "error", errorMsg,
                                                "rootCause", rootCause.getClass().getSimpleName() + ": " + rootMsg,
                                                "stackTrace", stackSnippet.toString().trim()
                                        ),
                                        emissionTraceId
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
        StreamPriority priority = StreamPriority.NORMAL;
        if (entry.isGenerated()) {
            priority = StreamPriority.PRIORITIZED_FRONT;
        } else if (djStateService.isDjEnabled(brandName)) {
            priority = StreamPriority.PRIORITIZED_FRONT;
        }
        return emitTimelineEntry(brandName, liveScene, entry, brandZone, priority.getValue(), UUID.randomUUID());
    }

    public Uni<Void> emitTimelineEntry(String brandName, LiveScene liveScene, TimelineEntry entry, ZoneId brandZone, UUID emissionTraceId) {
        StreamPriority priority = StreamPriority.NORMAL;
        if (entry.isGenerated()) {
            priority = StreamPriority.PRIORITIZED_FRONT;
        } else if (djStateService.isDjEnabled(brandName)) {
            priority = StreamPriority.PRIORITIZED_FRONT;
        }
        return emitTimelineEntry(brandName, liveScene, entry, brandZone, priority.getValue(), emissionTraceId);
    }

    public Uni<Void> emitTimelineEntry(String brandName, LiveScene liveScene, TimelineEntry entry, ZoneId brandZone, int priority) {
        return emitTimelineEntry(brandName, liveScene, entry, brandZone, priority, UUID.randomUUID());
    }

    public Uni<Void> emitTimelineEntry(String brandName, LiveScene liveScene, TimelineEntry entry, ZoneId brandZone, int priority, UUID emissionTraceId) {
        LOGGER.infof("Emitting entry #%d for scene '%s' brand '%s' (generated=%s, jingle=%s)",
                entry.getSequenceNumber(), liveScene.getSceneTitle(), brandName,
                entry.isGenerated(), entry.isHasJingle());
        return brandPool.get(brandName)
                .chain(stream -> {
                    return aiAgentService.getById(stream.getAiAgentId())
                            .chain(agent -> {
                                if (entry.isGenerated()) {
                                    return generatedContentEmitter.send(brandName, liveScene, entry, agent, stream, brandZone, priority, emissionTraceId)
                                            .onFailure().invoke(err -> LOGGER.error(String.format(
                                                    "Generated content emitter failed for entry #%d scene '%s': %s",
                                                    entry.getSequenceNumber(), liveScene.getSceneTitle(), err.getMessage()), err));
                                }
                                if (entry.isHasJingle()) {
                                    return jingleSongEmitter.send(brandName, liveScene, entry, agent, stream, brandZone, priority, emissionTraceId)
                                            .onFailure().invoke(err -> LOGGER.error(String.format(
                                                    "Jingle emitter failed for entry #%d scene '%s': %s",
                                                    entry.getSequenceNumber(), liveScene.getSceneTitle(), err.getMessage()), err));
                                }
                                return songEmitter.send(brandName, liveScene, entry, agent, stream, brandZone, priority, emissionTraceId)
                                        .onFailure().invoke(err -> LOGGER.error(String.format(
                                                "Song emitter failed for entry #%d scene '%s': %s",
                                                entry.getSequenceNumber(), liveScene.getSceneTitle(), err.getMessage()), err));
                            });
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

    private void assignBoostPrompt(TimelineEntry entry, LiveScene scene) {
        List<ScenePrompt> introPrompts = scene.getIntroPrompts();
        if (introPrompts == null || introPrompts.isEmpty()) return;
        List<ScenePrompt> active = introPrompts.stream().filter(ScenePrompt::isActive).toList();
        if (active.isEmpty()) return;
        ScenePrompt picked = active.get(RANDOM.nextInt(active.size()));
        List<SongEntry> songs = entry.getSongs();
        for (int i = 0; i < songs.size(); i++) {
            if (!introAtIndex(entry.getMixingStrategy(), i)) continue;
            songs.get(i).getPromptEntry().setPromptId(picked.getPromptId());
        }
    }

    private static boolean introAtIndex(MixingType type, int index) {
        if (type == MixingType.SONG_INTRO_SONG) return index == 1;
        return true;
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