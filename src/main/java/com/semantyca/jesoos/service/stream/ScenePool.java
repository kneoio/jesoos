package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.model.stream.LiveScene;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

@Getter
@ApplicationScoped
public class ScenePool {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScenePool.class);
    
    private final ConcurrentHashMap<String, LiveScene> activeScenes = new ConcurrentHashMap<>();

    public void setActiveScene(String brandName, LiveScene scene) {
        activeScenes.put(brandName, scene);
    }

    public LiveScene getActiveScene(String brandName) {
        return activeScenes.get(brandName);
    }

    public void removeActiveScene(String brandName) {
        LiveScene removed = activeScenes.remove(brandName);
        if (removed != null) {
            LOGGER.info("Removed active scene '{}' for brand: {}", 
                    removed.getSceneTitle(), brandName);
        }
    }

}
