package com.semantyca.jesoos.service.chat.ad;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AdSessionManager {

    private static final Logger LOGGER = Logger.getLogger(AdSessionManager.class);

    private final ConcurrentHashMap<String, AdSessionData> sessions = new ConcurrentHashMap<>();

    public void start(String connectionId, AdSessionData session) {
        sessions.put(connectionId, session);
        LOGGER.infof("[AD] session started connectionId=%s brand=%s", connectionId, session.getBrandSlug());
    }

    public Optional<AdSessionData> get(String connectionId) {
        return Optional.ofNullable(sessions.get(connectionId));
    }

    public void update(String connectionId, AdSessionData session) {
        sessions.put(connectionId, session);
    }

    public void end(String connectionId) {
        sessions.remove(connectionId);
        LOGGER.infof("[AD] session ended connectionId=%s", connectionId);
    }

    public boolean isActive(String connectionId) {
        return sessions.containsKey(connectionId);
    }
}
