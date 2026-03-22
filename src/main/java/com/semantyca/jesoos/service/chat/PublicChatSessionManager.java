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
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class PublicChatSessionManager {
    private static final String HARDCODED_CODE = "1234";
    private static final long SESSION_EXPIRY_SECONDS = 86400;  //24 hours
    private DB db;
    private HTreeMap<String, PublicChatSession> sessions;

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

    //@Scheduled(every = "30s")
    void debugSessions() {
        System.out.println("=== MapDB Sessions Debug ===");
        System.out.println("Total sessions: " + sessions.size());
        sessions.forEach((token, session) -> {
            System.out.println("Token: " + token);
            System.out.println("  Email: " + session.email());
            System.out.println("  Expires: " + session.expiresAt());
            System.out.println("  Expired: " + session.isExpired());
        });
        System.out.println("===========================");
    }

    public String generateAndStoreCode(String email) {
        return HARDCODED_CODE;
    }

    public VerificationResult verifyCode(String code, String email) {
        if (!HARDCODED_CODE.equals(code)) {
            return new VerificationResult(false, "Invalid verification code", null);
        }

        String sessionToken = createSession(email.toLowerCase());
        return new VerificationResult(true, "Verification successful", sessionToken);
    }

    private String createSession(String email) {
        // Remove existing sessions for this email
        sessions.entrySet().removeIf(entry -> entry.getValue().email().equals(email));

        String token = UUID.randomUUID().toString();
        sessions.put(token, new PublicChatSession(email, Instant.now().plusSeconds(SESSION_EXPIRY_SECONDS)));
        db.commit();
        return token;
    }

    public String validateSessionAndGetEmail(String token) {
        PublicChatSession session = sessions.get(token);
        return session != null ? session.email() : null;
    }

    public void storeUserToken(String userToken, String email) {
        sessions.put(userToken, new PublicChatSession(email, Instant.now().plusSeconds(SESSION_EXPIRY_SECONDS)));
        db.commit();
    }


    public record VerificationResult(boolean success, String message, String sessionToken) {}
}
