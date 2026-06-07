package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.model.cnst.BoostType;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class DjStateService {
    private static final Logger LOGGER = Logger.getLogger(DjStateService.class);

    private final ConcurrentHashMap<String, Boolean> djEnabledMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BoostState> djBoostMap = new ConcurrentHashMap<>();

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

    public void activateBoost(String brandName, int entries, BoostType type) {
        djBoostMap.put(brandName, new BoostState(new AtomicInteger(entries), type));
        LOGGER.infof("DJ boost activated for brand: %s (%d entries, type=%s)", brandName, entries, type);
    }

    public BoostType consumeBoostEntry(String brandName) {
        BoostState state = djBoostMap.get(brandName);
        if (state == null) return null;
        int val = state.remaining().decrementAndGet();
        if (val < 0) {
            djBoostMap.remove(brandName);
            return null;
        }
        if (val == 0) djBoostMap.remove(brandName);
        return state.type();
    }

    public void remove(String brandName) {
        djEnabledMap.remove(brandName);
        djBoostMap.remove(brandName);
        LOGGER.infof("DJ state removed for brand: %s", brandName);
    }
}
