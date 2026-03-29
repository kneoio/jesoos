package com.semantyca.jesoos.service.stream;

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

    @Scheduled(every = "15s",  delay = 5, delayUnit = TimeUnit.SECONDS)
    void scheduleActiveScenes() {
        scenePool.getActiveScenes().forEach((brandName, scene) -> {
            if (!scene.getTimeline().isEmpty()) {
                staggeredSongScheduler.scheduleSceneSongs(brandName, scene);
            } else {
                LOGGER.infof("Scene %s for brand %s has an empty timeline. Skipping.", scene.getSceneTitle(), brandName);
            }
        });
    }
}
