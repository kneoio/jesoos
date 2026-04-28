package com.semantyca.jesoos.service.chat.ots;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class OtsSessionManager {

    private static final Logger LOGGER = Logger.getLogger(OtsSessionManager.class);

    private final ConcurrentHashMap<String, OtsSessionData> sessions = new ConcurrentHashMap<>();

    public void start(String connectionId, OtsSessionData session) {
        sessions.put(connectionId, session);
        LOGGER.infof("[OTS] session started connectionId=%s script=%s vars=%s",
                connectionId, session.getScriptName(), session.getVarNames());
    }

    public Optional<OtsSessionData> get(String connectionId) {
        return Optional.ofNullable(sessions.get(connectionId));
    }

    public void update(String connectionId, OtsSessionData session) {
        sessions.put(connectionId, session);
    }

    public void end(String connectionId) {
        sessions.remove(connectionId);
        LOGGER.infof("[OTS] session ended connectionId=%s", connectionId);
    }

    public boolean isActive(String connectionId) {
        return sessions.containsKey(connectionId);
    }
}
