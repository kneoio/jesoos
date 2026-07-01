package com.semantyca.jesoos.service.live;

import com.semantyca.mixpla.model.cnst.Boost;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class DjStateService {
    private static final Logger LOGGER = Logger.getLogger(DjStateService.class);

    private final ConcurrentHashMap<String, Boolean> djEnabledMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LiveBoostState> liveBoostMap = new ConcurrentHashMap<>();

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

    public void activateLiveBoost(String brandName, int entries, Boost type) {
        liveBoostMap.put(brandName, new LiveBoostState(new AtomicInteger(entries), type));
        LOGGER.infof("Live boost activated for brand: %s (%d entries, type=%s)", brandName, entries, type);
    }

    public Boost consumeLiveBoostEntry(String brandName) {
        LiveBoostState state = liveBoostMap.get(brandName);
        if (state == null) return null;
        int val = state.remaining().decrementAndGet();
        if (val < 0) {
            liveBoostMap.remove(brandName);
            return null;
        }
        if (val == 0) liveBoostMap.remove(brandName);
        return state.type();
    }

    public void remove(String brandName) {
        djEnabledMap.remove(brandName);
        liveBoostMap.remove(brandName);
        LOGGER.infof("DJ state removed for brand: %s", brandName);
    }
}
