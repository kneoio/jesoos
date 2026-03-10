package com.semantyca.djinn.service.stream;

import com.semantyca.djinn.model.stream.LiveScene;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ScenePool {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScenePool.class);
    
    private final ConcurrentHashMap<String, LiveScene> activeScenes = new ConcurrentHashMap<>();

    public void addScene(String brandName, LiveScene scene) {
        activeScenes.put(brandName, scene);
        LOGGER.info("Added scene '{}' to pool for brand: {}", scene.getSceneTitle(), brandName);
    }

    public LiveScene getActiveScene(String brandName) {
        return activeScenes.get(brandName);
    }

    public void removeScene(String brandName) {
        LiveScene removed = activeScenes.remove(brandName);
        if (removed != null) {
            LOGGER.info("Removed scene '{}' from pool for brand: {}", removed.getSceneTitle(), brandName);
        }
    }

    public Map<String, LiveScene> getAllActiveScenes() {
        return Map.copyOf(activeScenes);
    }

    public boolean hasActiveScene(String brandName) {
        return activeScenes.containsKey(brandName);
    }
}
