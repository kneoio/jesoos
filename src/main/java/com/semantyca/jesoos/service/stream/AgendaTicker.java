package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.StreamAgenda;
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

    @Inject
    BrandPool brandPool;

    @Inject
    ScenePool scenePool;

    @Inject
    StaggeredSongScheduler staggeredSongScheduler;

    @Inject
    MetricPublisher metricPublisher;

    @Scheduled(every = "60s")
    void tick() {
        Map<String, StreamAgenda> agendas = brandPool.getAll();
        if (agendas.isEmpty()) {
            return;
        }

        agendas.forEach((key, agenda) -> {
            String[] parts = key.split(":", 2);
            String brand = parts[0];
            ZoneId zoneId = agenda.getTimeZone() != null ? agenda.getTimeZone() : ZoneId.of("UTC");
            LocalDateTime nowDateTime = ZonedDateTime.now(zoneId).toLocalDateTime();
            LocalTime nowTime = nowDateTime.toLocalTime();

            List<LiveScene> scenes = agenda.getLiveScenes();
            for (int i = 0; i < scenes.size(); i++) {
                LiveScene scene = scenes.get(i);

                if (scene.getOriginalStartTime() == null) continue;

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

                LiveScene nextScene = (i < scenes.size() - 1) ? scenes.get(i + 1) : null;
                boolean isActive = scene.isActiveAt(nowTime, nextScene != null ? nextScene.getOriginalStartTime() : null);

                if (!isActive) continue;

                LOGGER.infof("Checking: %s, start: %s, isActive: %s", scene.getSceneTitle(), scene.getOriginalStartTime(), isActive);
                long lagSeconds = calculateLagSeconds(nowTime, scene.getOriginalStartTime());
                FireReason reason = lagSeconds < 30 ? FireReason.ON_TIME : FireReason.REBOOT;
                processScene(brand, key, scene, reason);
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

    private void processScene(String brand, String agendaKey, LiveScene scene, FireReason reason) {
        scene.setSentToQueueAt(LocalDateTime.now());
        scene.setFireReason(reason);

        LOGGER.infof("Processing scene '%s' for brand: %s, agenda: %s, reason: %s, traceId: {}",
                scene.getSceneTitle(), brand, agendaKey, reason, scene.getTraceId());

        metricPublisher.publishMetric(brand, com.semantyca.mixpla.dto.queue.metric.MetricEventType.INFORMATION, 
                "scene_processing_started",
                Map.of(
                    "scene", scene.getSceneTitle(),
                    "sceneId", scene.getSceneId().toString(),
                    "fireReason", reason.toString(),
                    "songCount", scene.getSongs().size()
                ), scene.getTraceId());

        scenePool.addScene(brand, scene);
        LOGGER.infof("Added scene '%s' to ScenePool for brand: %s (contains %d songs), traceId: {}",
                scene.getSceneTitle(), brand, scene.getSongs().size(), scene.getTraceId());

        staggeredSongScheduler.scheduleSceneSongs(brand, scene);
    }
}