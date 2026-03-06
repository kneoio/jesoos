package com.semantyca.djinn.service.stream;

import com.semantyca.djinn.model.stream.StreamAgenda;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StreamAgendaManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamAgendaManager.class);

    private final Map<String, StreamAgenda> agendas = new ConcurrentHashMap<>();

    public void register(String brand, UUID scriptId, StreamAgenda agenda) {
        String key = buildKey(brand, scriptId);
        agendas.put(key, agenda);
        LOGGER.info("Registered agenda for brand: {}, script: {}, key: {}, total scenes: {}",
                brand, scriptId, key, agenda.getLiveScenes().size());
    }

    public StreamAgenda get(String brand, UUID scriptId) {
        return agendas.get(buildKey(brand, scriptId));
    }

    public StreamAgenda getByKey(String key) {
        return agendas.get(key);
    }

    public StreamAgenda remove(String brand, UUID scriptId) {
        return agendas.remove(buildKey(brand, scriptId));
    }

    public StreamAgenda removeByKey(String key) {
        return agendas.remove(key);
    }

    public Map<String, StreamAgenda> getAll() {
        return Map.copyOf(agendas);
    }

    public boolean contains(String brand, UUID scriptId) {
        return agendas.containsKey(buildKey(brand, scriptId));
    }

    public int size() {
        return agendas.size();
    }

    public void clear() {
        agendas.clear();
    }

    private String buildKey(String brand, UUID scriptId) {
        return brand + ":" + scriptId;
    }
}
