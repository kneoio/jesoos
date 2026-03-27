package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.model.stream.TimelineEntryStatus;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class SceneTicker {
    private static final Logger LOGGER = Logger.getLogger(SceneTicker.class);

    @Inject
    ScenePool scenePool;

    @Inject
    StaggeredSongScheduler staggeredSongScheduler;

    @Scheduled(every = "60s",  delay = 15, delayUnit = TimeUnit.SECONDS)
    void scheduleActiveScenes() {
        scenePool.getActiveScenes().forEach((brandName, scene) -> {
            if (scene != null && scene.getTimeline() != null && !scene.getTimeline().isEmpty()) {
                boolean hasUnscheduledEntries = scene.getTimeline().stream()
                        .anyMatch(entry -> entry.getStatus() == TimelineEntryStatus.PENDING);
                
                if (hasUnscheduledEntries) {
                    LOGGER.infof("Scheduling timeline for active scene '{}' (brand: {})", 
                            scene.getSceneTitle(), brandName);
                    staggeredSongScheduler.scheduleSceneSongs(brandName, scene);
                }
            }
        });
    }
}
