package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.model.stream.LiveScene;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.AccessLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

@Getter
@ApplicationScoped
public class ScenePool {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScenePool.class);

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
            LOGGER.info("Removed active scene '{}' for brand: {}",
                    removed.getSceneTitle(), brandName);
        }
    }

    public void clear() {
        activeScenes.clear();
    }

    @PreDestroy
    void cleanup() {
        LOGGER.info("ScenePool cleanup: clearing {} active scenes", activeScenes.size());
        activeScenes.clear();
    }
}
