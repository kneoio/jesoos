package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.officeframe.service.LabelService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ListenerLabelCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerLabelCache.class);

    private static final String[] KNOWN_IDENTIFIERS = {"artist"};

    private final Map<String, UUID> cache = new ConcurrentHashMap<>();

    @Inject
    LabelService labelService;

    void onStart(@Observes StartupEvent event) {
        reload();
    }

    public void reload() {
        for (String identifier : KNOWN_IDENTIFIERS) {
            try {
                var label = labelService.findByIdentifier(identifier)
                        .await().atMost(java.time.Duration.ofSeconds(10));
                if (label != null) {
                    cache.put(identifier, label.getId());
                    LOGGER.info("[ListenerLabelCache] Resolved '{}' -> {}", identifier, label.getId());
                } else {
                    LOGGER.warn("[ListenerLabelCache] Label not found for identifier '{}'", identifier);
                }
            } catch (Exception err) {
                LOGGER.error("[ListenerLabelCache] Failed to resolve label '{}'", identifier, err);
            }
        }
    }

    public UUID get(String identifier) {
        return cache.get(identifier);
    }

    public io.smallrye.mutiny.Uni<UUID> getOrLoad(String identifier) {
        UUID cached = cache.get(identifier);
        if (cached != null) {
            return io.smallrye.mutiny.Uni.createFrom().item(cached);
        }
        return labelService.findByIdentifier(identifier)
                .onItem().invoke(label -> {
                    if (label != null) {
                        cache.put(identifier, label.getId());
                        LOGGER.info("[ListenerLabelCache] Lazy-resolved '{}' -> {}", identifier, label.getId());
                    }
                })
                .map(label -> label != null ? label.getId() : null);
    }
}
