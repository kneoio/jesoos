package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@ApplicationScoped
public class ScenePool {
    private static final Logger LOGGER = Logger.getLogger(ScenePool.class);

    private final ConcurrentHashMap<String, LiveScene> activeScenes = new ConcurrentHashMap<>();

    @Inject
    private MetricPublisher metricPublisher;

    @Inject
    @Getter(AccessLevel.NONE)
    StaggeredSongScheduler staggeredSongScheduler;

    public void setActiveScene(String brandName, LiveScene scene) {
        activeScenes.put(brandName, scene);
    }

    public LiveScene getActiveScene(String brandName) {
        return activeScenes.get(brandName);
    }

    public void removeActiveScene(String brandName) {
        LiveScene removed = activeScenes.remove(brandName);
        if (removed != null) {
            staggeredSongScheduler.cancelBrandTimers(brandName);
            publishSceneSummary(brandName, removed);
            LOGGER.infof("Removed active scene '%s' for brand: {}",
                    removed.getSceneTitle(), brandName);
        }
    }

    private void publishSceneSummary(String brandName, LiveScene scene) {
        List<String> summary = scene.getTimeline().stream()
                .map(e -> "#" + e.getSequenceNumber() + "@" + TimeFormatUtil.formatTime(e.getScheduledEmissionTime()) + "[" + e.getStatus() + "]")
                .toList();
        metricPublisher.publishMetric(
                brandName,
                MetricEventType.INFORMATION,
                ProcessType.FLOW,
                "scene_stopped",
                Map.of(
                        "scene", scene.getSceneTitle(),
                        "entries", summary
                ),
                scene.getTraceId()
        );
    }

    @PreDestroy
    void cleanup() {
        LOGGER.infof("ScenePool cleanup: clearing %s active scenes", activeScenes.size());
        activeScenes.clear();
    }
}
