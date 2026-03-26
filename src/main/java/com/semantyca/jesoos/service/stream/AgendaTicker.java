package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
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
    private final StaggeredSongScheduler staggeredSongScheduler;
    private final MetricPublisher metricPublisher;

    @Inject
    public AgendaTicker(BrandPool brandPool, ScenePool scenePool, StaggeredSongScheduler staggeredSongScheduler, MetricPublisher metricPublisher) {
        this.brandPool = brandPool;
        this.scenePool = scenePool;
        this.staggeredSongScheduler = staggeredSongScheduler;
        this.metricPublisher = metricPublisher;
    }

    @Scheduled(every = "60s")
    void tick() {
        Map<String, StreamAgenda> agendas = brandPool.getAll();

        agendas.forEach((brandSlug, agenda) -> {
            ZoneId zoneId = agenda.getTimeZone();
            LocalDateTime nowDateTime = ZonedDateTime.now(zoneId).toLocalDateTime();
            LocalTime nowTime = nowDateTime.toLocalTime();

            List<LiveScene> scenes = agenda.getLiveScenes();
            for (int i = 0; i < scenes.size(); i++) {
                LiveScene scene = scenes.get(i);

                if (scene.getSentToQueueAt() != null) {
                    if (scene.getSentToQueueAt().toLocalDate().equals(nowDateTime.toLocalDate())) {
                        LOGGER.debugf("Skipping scene '%s' - already sent to queue today at %s",
                                scene.getSceneTitle(), scene.getSentToQueueAt());
                        continue;
                    }
                }

                if (scene.isOneTimeRun() && scene.getLastRunDate() != null) {
                    if (scene.getLastRunDate().toLocalDate().equals(nowDateTime.toLocalDate())) {
                        LOGGER.debugf("Skipping one-time scene '%s' - already ran today at %s",
                                scene.getSceneTitle(), scene.getLastRunDate());
                        continue;
                    }
                }

                LocalTime nextSceneStartTime = null;
                if (i < scenes.size() - 1) {
                    nextSceneStartTime = scenes.get(i + 1).getOriginalStartTime();
                }

                boolean isActive = scene.isActiveAt(nowTime, nextSceneStartTime);

                if (!isActive) continue;

                LOGGER.infof("Checking: %s, start: %s", scene.getSceneTitle(), scene.getOriginalStartTime());
                long lagSeconds = calculateLagSeconds(nowTime, scene.getOriginalStartTime());
                TriggerContext triggerContext = lagSeconds < 30 ? TriggerContext.ON_TIME : TriggerContext.LATE;
                processScene(brandSlug, scene, triggerContext);
            }
        });
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
        scene.setSentToQueueAt(LocalDateTime.now());
        scene.setTriggerContext(triggerContext);

        LOGGER.infof("Processing scene '%s' for brand: %s, triggerContext: %s, traceId: {}",
                scene.getSceneTitle(), brand, triggerContext, scene.getTraceId());

        metricPublisher.publishMetric(brand, MetricEventType.INFORMATION,
                "scene_processing_started",
                Map.of(
                    "scene", scene.getSceneTitle(),
                    "sceneId", scene.getSceneId().toString(),
                    "triggerContext", triggerContext.toString(),
                    "songCount", scene.getSongs().size()
                ), scene.getTraceId());

        scenePool.addScene(brand, scene);
        LOGGER.infof("Added scene '%s' to ScenePool for brand: %s (contains %d songs), traceId: {}",
                scene.getSceneTitle(), brand, scene.getSongs().size(), scene.getTraceId());

        staggeredSongScheduler.scheduleSceneSongs(brand, scene);
    }
}
