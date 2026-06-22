package com.semantyca.jesoos.service.chat;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ChatSessionTracer {

    private static final long SESSION_GAP_MS = 60 * 60 * 1000L;

    private record SessionState(UUID traceId, long lastTs, LocalDate date) {}

    private final Map<String, SessionState> sessionByBrand = new ConcurrentHashMap<>();

    public UUID resolveTraceId(String brand) {
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        SessionState current = sessionByBrand.get(brand);
        boolean newSession = current == null
                || !today.equals(current.date())
                || (now - current.lastTs()) > SESSION_GAP_MS;
        UUID traceId = newSession ? UUID.randomUUID() : current.traceId();
        sessionByBrand.put(brand, new SessionState(traceId, now, today));
        return traceId;
    }
}
