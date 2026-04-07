package com.semantyca.jesoos.service.chat;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.File;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Persistent session store (MapDB) for authenticated public chat sessions.
 * OTP generation and code verification are fully delegated to Keycloak.
 * This manager only stores the session token after successful Keycloak authentication.
 */
@ApplicationScoped
public class PublicChatSessionManager {

    private static final long SESSION_EXPIRY_SECONDS = 86400 * 30; // 30 days
    private static final long OTP_EXPIRY_SECONDS = 600; // 10 minutes

    private DB db;
    private HTreeMap<String, PublicChatSession> sessions;
    private final ConcurrentHashMap<String, PendingOtp> pendingOtps = new ConcurrentHashMap<>();

    public record PendingOtp(String code, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }

    @SuppressWarnings("unchecked")
    @PostConstruct
    void init() {
        new File("sessions_data").mkdirs();
        this.db = DBMaker
                .fileDB("sessions_data/chat-sessions.db")
                .transactionEnable()
                .make();
        this.sessions = db
                .hashMap("sessions", Serializer.STRING, Serializer.JAVA)
                .expireAfterCreate(SESSION_EXPIRY_SECONDS, TimeUnit.SECONDS)
                .createOrOpen();
    }

    @PreDestroy
    void shutdown() {
        db.close();
    }

    @Scheduled(every = "1h")
    void cleanupExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
        db.commit();
    }

    public void storePendingOtp(String email, String code) {
        pendingOtps.put(email.toLowerCase(),
                new PendingOtp(code, Instant.now().plusSeconds(OTP_EXPIRY_SECONDS)));
    }

    public boolean verifyAndConsumePendingOtp(String email, String code) {
        PendingOtp otp = pendingOtps.get(email.toLowerCase());
        if (otp == null || otp.isExpired() || !otp.code().equals(code)) {
            return false;
        }
        pendingOtps.remove(email.toLowerCase());
        return true;
    }

    /**
     * Stores a session token after successful OTP verification.
     */
    public void storeUserToken(String token, String email) {
        // Remove any existing sessions for this email first
        sessions.entrySet().removeIf(e -> e.getValue().email().equals(email.toLowerCase()));
        sessions.put(token, new PublicChatSession(email.toLowerCase(), Instant.now().plusSeconds(SESSION_EXPIRY_SECONDS)));
        db.commit();
    }

    /**
     * Validates a session token and returns the associated email, or null if invalid/expired.
     */
    public String validateSessionAndGetEmail(String token) {
        PublicChatSession session = sessions.get(token);
        if (session == null || session.isExpired()) {
            if (session != null) {
                sessions.remove(token);
                db.commit();
            }
            return null;
        }
        return session.email();
    }

    /**
     * Generates a fresh token for an existing session (e.g. for token rotation).
     */
    public String rotateToken(String oldToken) {
        String email = validateSessionAndGetEmail(oldToken);
        if (email == null) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        String newToken = UUID.randomUUID().toString();
        storeUserToken(newToken, email);
        sessions.remove(oldToken);
        db.commit();
        return newToken;
    }
}
