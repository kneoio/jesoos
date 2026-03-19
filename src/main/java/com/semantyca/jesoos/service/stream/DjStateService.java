package com.semantyca.jesoos.service.stream;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DjStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DjStateService.class);
    
    private final ConcurrentHashMap<String, Boolean> djEnabledMap = new ConcurrentHashMap<>();

    public void enableDj(String brandName) {
        djEnabledMap.put(brandName, true);
        LOGGER.info("DJ enabled for brand: {}", brandName);
    }

    public void disableDj(String brandName) {
        djEnabledMap.put(brandName, false);
        LOGGER.info("DJ disabled for brand: {} (TTS cost saving mode)", brandName);
    }

    public boolean isDjEnabled(String brandName) {
        return djEnabledMap.getOrDefault(brandName, true);
    }

    public void remove(String brandName) {
        djEnabledMap.remove(brandName);
        LOGGER.info("DJ state removed for brand: {}", brandName);
    }
}
