package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.model.stream.LiveScene;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.AccessLevel;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

@Getter
@ApplicationScoped
public class ScenePool {
    private static final Logger LOGGER = Logger.getLogger(ScenePool.class);

    private final ConcurrentHashMap<String, LiveScene> activeScenes = new ConcurrentHashMap<>();

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
            staggeredSongScheduler.publishSceneSummary(brandName, removed);
            LOGGER.infof("Removed active scene '%s' for brand: {}",
                    removed.getSceneTitle(), brandName);
        }
    }

    public void clear() {
        activeScenes.clear();
    }

    @PreDestroy
    void cleanup() {
        LOGGER.infof("ScenePool cleanup: clearing %s active scenes", activeScenes.size());
        activeScenes.clear();
    }
}
