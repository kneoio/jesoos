package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.service.agenda.TriggerContext;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AgendaTicker {
    private static final Logger LOGGER = Logger.getLogger(AgendaTicker.class);
    /** Floor for a one-time scene's active window so the 60s tick reliably catches its start. */
    private static final int MIN_ONE_TIME_WINDOW_SECONDS = 90;
    private final BrandPool brandPool;
    private final ScenePool scenePool;
    private final MetricPublisher metricPublisher;
    private final StaggeredSongScheduler staggeredSongScheduler;

    @Inject
    public AgendaTicker(BrandPool brandPool, ScenePool scenePool, MetricPublisher metricPublisher, StaggeredSongScheduler staggeredSongScheduler) {
        this.brandPool = brandPool;
        this.scenePool = scenePool;
        this.metricPublisher = metricPublisher;
        this.staggeredSongScheduler = staggeredSongScheduler;
    }

    @Scheduled(every = "60s")
    void tick() {
        Map<String, StreamAgenda> agendas = brandPool.getAll();

        agendas.forEach((brandSlug, agenda) -> {
            ZoneId zoneId = agenda.getTimeZone();
            LocalDateTime nowDateTime = ZonedDateTime.now(zoneId).toLocalDateTime();
            LocalTime nowTime = nowDateTime.toLocalTime();

            List<LiveScene> scenes = agenda.getLiveScenes();

            // One-time scenes preempt the loop while they are within their own window;
            // otherwise the current loop scene is the baseline.
            LiveScene selected = findActiveOneTime(scenes, nowTime);
            if (selected == null) {
                selected = findLoopingScene(scenes, nowTime);
            }

            LiveScene currentActive = scenePool.getActiveScene(brandSlug);

            if (selected == null) {
                if (currentActive != null) {
                    LOGGER.infof("No active scene for brand: %s, removing '%s' from pool",
                            brandSlug, currentActive.getSceneTitle());
                    staggeredSongScheduler.cancelBrandTimers(brandSlug);
                    scenePool.removeActiveScene(brandSlug);
                }
                return;
            }

            if (currentActive == selected) {
                LOGGER.debugf("Scene '%s' already active for brand: %s", selected.getSceneTitle(), brandSlug);
                return;
            }

            if (currentActive != null) {
                LOGGER.infof("Active scene changed for brand %s: '%s' -> '%s'",
                        brandSlug, currentActive.getSceneTitle(), selected.getSceneTitle());
                staggeredSongScheduler.cancelBrandTimers(brandSlug);
                scenePool.removeActiveScene(brandSlug);
            }

            LOGGER.infof("Active scene found: %s, start: %s", selected.getSceneTitle(), selected.getOriginalStartTime());
            long lagSeconds = calculateLagSeconds(nowTime, selected.getOriginalStartTime());
            TriggerContext triggerContext = lagSeconds < 30 ? TriggerContext.ON_TIME : TriggerContext.LATE;
            processScene(brandSlug, selected, triggerContext);
        });
    }

    /**
     * The one-time scene whose window [start, start + duration) currently contains now and that
     * has not finished. Latest-starting wins. Bounding by the scene's own duration (not the next
     * scene in list order) is what keeps stale past instances from lingering as "active".
     */
    private LiveScene findActiveOneTime(List<LiveScene> scenes, LocalTime nowTime) {
        LiveScene best = null;
        for (LiveScene scene : scenes) {
            if (!scene.isOneTimeRun() || scene.isFinished()) continue;
            LocalTime start = scene.getOriginalStartTime();
            if (start == null || start.isAfter(nowTime)) continue;
            int windowSeconds = Math.max(scene.getDurationSeconds(), MIN_ONE_TIME_WINDOW_SECONDS);
            if (calculateLagSeconds(nowTime, start) >= windowSeconds) continue;
            if (best == null || start.isAfter(best.getOriginalStartTime())) best = scene;
        }
        return best;
    }

    private LiveScene findLoopingScene(List<LiveScene> scenes, LocalTime nowTime) {
        LiveScene latestBeforeNow = null;
        LiveScene latestOverall = null;
        for (LiveScene scene : scenes) {
            if (scene.isOneTimeRun()) continue;
            LocalTime start = scene.getOriginalStartTime();
            if (start == null) continue;
            if (latestOverall == null || start.isAfter(latestOverall.getOriginalStartTime())) {
                latestOverall = scene;
            }
            if (!start.isAfter(nowTime) &&
                    (latestBeforeNow == null || start.isAfter(latestBeforeNow.getOriginalStartTime()))) {
                latestBeforeNow = scene;
            }
        }
        // The agenda spans one 06:00->06:00 window. If no loop has started yet at this hour
        // (all starts are later today), the active loop is the last one from before midnight —
        // it wraps across the rebuild boundary to cover 00:00 until the first loop after 06:00.
        return latestBeforeNow != null ? latestBeforeNow : latestOverall;
    }

    private long calculateLagSeconds(LocalTime nowTime, LocalTime sceneStartTime) {
        int nowSeconds = nowTime.toSecondOfDay();
        int startSeconds = sceneStartTime.toSecondOfDay();

        if (nowSeconds >= startSeconds) {
            return nowSeconds - startSeconds;
        } else {
            return (86400 - startSeconds) + nowSeconds;
        }
    }

    private void processScene(String brand, LiveScene scene, TriggerContext triggerContext) {
        scene.setTriggerContext(triggerContext);

        LOGGER.infof("Processing scene '%s' for brand: %s, triggerContext: %s, traceId: %s",
                scene.getSceneTitle(), brand, triggerContext, scene.getTraceId());

        metricPublisher.publishMetric(brand, MetricEventType.INFORMATION, ProcessType.CRON,
                "scene_started",
                Map.of(
                    "scene", scene.getSceneTitle(),
                    "sceneId", scene.getSceneId().toString(),
                    "triggerContext", triggerContext.toString()

                ), scene.getTraceId());

        scenePool.setActiveScene(brand, scene);
        LOGGER.infof("Set active scene '%s' for brand: %s , traceId: %s",
                scene.getSceneTitle(), brand, scene.getTraceId());
    }
}
