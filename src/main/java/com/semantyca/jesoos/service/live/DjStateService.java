package com.semantyca.jesoos.service.live;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;


import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DjStateService {
    private static final Logger LOGGER = Logger.getLogger(DjStateService.class);
    
    private final ConcurrentHashMap<String, Boolean> djEnabledMap = new ConcurrentHashMap<>();

    public void enableDj(String brandName) {
        djEnabledMap.put(brandName, true);
        LOGGER.infof("DJ enabled for brand: %s", brandName);
    }

    public void disableDj(String brandName) {
        djEnabledMap.put(brandName, false);
        LOGGER.infof("DJ disabled for brand: %s (TTS cost saving mode)", brandName);
    }

    public boolean isDjEnabled(String brandName) {
        return djEnabledMap.getOrDefault(brandName, false);
    }

    public void remove(String brandName) {
        djEnabledMap.remove(brandName);
        LOGGER.infof("DJ state removed for brand: %s", brandName);
    }
}
