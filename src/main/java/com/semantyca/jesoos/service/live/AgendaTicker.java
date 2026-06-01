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
            LiveScene activeSceneFound = null;

            for (int i = 0; i < scenes.size(); i++) {
                LiveScene scene = scenes.get(i);
                LocalTime nextSceneStartTime = scenes.get((i + 1) % scenes.size()).getOriginalStartTime();

                boolean isActive = scene.isActiveAt(nowTime, nextSceneStartTime);

                if (!isActive) continue;
                if (scene.isOneTimeRun() && scene.isFinished()) continue;

                activeSceneFound = scene;

                LiveScene currentActive = scenePool.getActiveScene(brandSlug);
                if (currentActive != null && currentActive == scene) {
                    LOGGER.debugf("Scene '%s' is already active for brand: %s",
                            scene.getSceneTitle(), brandSlug);
                    continue;
                }

                LOGGER.infof("Active scene found: %s, start: %s", scene.getSceneTitle(), scene.getOriginalStartTime());
                long lagSeconds = calculateLagSeconds(nowTime, scene.getOriginalStartTime());
                TriggerContext triggerContext = lagSeconds < 30 ? TriggerContext.ON_TIME : TriggerContext.LATE;
                processScene(brandSlug, scene, triggerContext);
            }
            
            // Check if current active oneTimeRun scene is finished after scheduling
            LiveScene currentActiveScene = scenePool.getActiveScene(brandSlug);
            if (currentActiveScene != null && currentActiveScene.isOneTimeRun() && currentActiveScene.isFinished()) {
                LiveScene loopingScene = findLoopingScene(scenes, nowTime);
                if (loopingScene != null) {
                    LOGGER.infof("One-time-run scene '%s' finished for brand: %s, resuming looping scene '%s'",
                            currentActiveScene.getSceneTitle(), brandSlug, loopingScene.getSceneTitle());
                    staggeredSongScheduler.cancelBrandTimers(brandSlug);
                    scenePool.removeActiveScene(brandSlug);
                    processScene(brandSlug, loopingScene, TriggerContext.ON_TIME);
                } else {
                    LOGGER.warnf("One-time-run scene '%s' finished for brand: %s, no looping scene found",
                            currentActiveScene.getSceneTitle(), brandSlug);
                }
                return;
            }
            
            if (currentActiveScene != null && activeSceneFound == null) {
                LOGGER.infof("No active scene found for brand: %s, removing scene '%s' from pool",
                        brandSlug, currentActiveScene.getSceneTitle());
                staggeredSongScheduler.cancelBrandTimers(brandSlug);
                scenePool.removeActiveScene(brandSlug);
            } else if (currentActiveScene != null && currentActiveScene != activeSceneFound) {
                LOGGER.infof("Active scene changed for brand: %s, removing old scene '%s' from pool",
                        brandSlug, currentActiveScene.getSceneTitle());
                staggeredSongScheduler.cancelBrandTimers(brandSlug);
                scenePool.removeActiveScene(brandSlug);
            }
        });
    }

    private LiveScene findLoopingScene(List<LiveScene> scenes, LocalTime nowTime) {
        LiveScene best = null;
        for (LiveScene scene : scenes) {
            if (scene.isOneTimeRun()) continue;
            LocalTime start = scene.getOriginalStartTime();
            if (!start.isAfter(nowTime)) {
                if (best == null || start.isAfter(best.getOriginalStartTime())) {
                    best = scene;
                }
            }
        }
        return best;
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
