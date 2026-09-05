package com.semantyca.jesoos.service.chat;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PublicChatSessionManager {

    private static final long SESSION_EXPIRY_SECONDS = 86400 * 30;
    private static final long OTP_EXPIRY_SECONDS = 600;
    private static final long UPLOAD_GATE_EXPIRY_SECONDS = 1800;

    private static final String TABLE = "mixpla__chat_sessions";

    private final Pool client;
    private final ConcurrentHashMap<String, PendingOtp> pendingOtps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> uploadGates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> recordGates = new ConcurrentHashMap<>();

    public record PendingOtp(String code, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }

    @Inject
    public PublicChatSessionManager(Pool client) {
        this.client = client;
    }

    // ---- OTP (in-memory, unchanged) ----

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

    public String getPendingOtp(String email) {
        PendingOtp otp = pendingOtps.get(email.toLowerCase());
        return (otp != null && !otp.isExpired()) ? otp.code() : null;
    }

    // ---- Upload gates (in-memory, per userId) ----

    public void grantUploadPermission(long userId) {
        uploadGates.put(userId, Instant.now().plusSeconds(UPLOAD_GATE_EXPIRY_SECONDS));
    }

    public boolean hasUploadPermission(long userId) {
        Instant expiry = uploadGates.get(userId);
        if (expiry == null || Instant.now().isAfter(expiry)) {
            uploadGates.remove(userId);
            return false;
        }
        return true;
    }

    public void grantRecordPermission(long userId) {
        recordGates.put(userId, Instant.now().plusSeconds(UPLOAD_GATE_EXPIRY_SECONDS));
    }

    public boolean hasRecordPermission(long userId) {
        Instant expiry = recordGates.get(userId);
        if (expiry == null || Instant.now().isAfter(expiry)) {
            recordGates.remove(userId);
            return false;
        }
        return true;
    }

    // ---- Session tokens (PostgreSQL-backed) ----

    public Uni<Void> storeUserToken(String token, String email) {
        String lower = email.toLowerCase();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(SESSION_EXPIRY_SECONDS);

        return client.withTransaction(tx ->
            tx.preparedQuery("DELETE FROM " + TABLE + " WHERE email = $1")
                .execute(Tuple.of(lower))
                .chain(() -> tx.preparedQuery(
                        "INSERT INTO " + TABLE + " (token, email, expires_at) VALUES ($1, $2, $3)")
                    .execute(Tuple.of(token, lower, expiresAt)))
        ).replaceWithVoid();
    }

    public Uni<String> validateSessionAndGetEmail(String token) {
        return client.preparedQuery(
                "SELECT email FROM " + TABLE + " WHERE token = $1 AND expires_at > NOW()")
            .execute(Tuple.of(token))
            .map(rows -> {
                if (rows.rowCount() == 0) return null;
                return rows.iterator().next().getString("email");
            });
    }

    public Uni<String> rotateToken(String oldToken) {
        return validateSessionAndGetEmail(oldToken)
            .onItem().transformToUni(email -> {
                if (email == null) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Invalid or expired token"));
                }
                String newToken = java.util.UUID.randomUUID().toString();
                OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(SESSION_EXPIRY_SECONDS);

                return client.withTransaction(tx ->
                    tx.preparedQuery("DELETE FROM " + TABLE + " WHERE token = $1")
                        .execute(Tuple.of(oldToken))
                        .chain(() -> tx.preparedQuery(
                                "INSERT INTO " + TABLE + " (token, email, expires_at) VALUES ($1, $2, $3)")
                            .execute(Tuple.of(newToken, email, expiresAt)))
                ).map(ignored -> newToken);
            });
    }

    public Uni<Void> deleteTokenByEmail(String email) {
        return client.preparedQuery("DELETE FROM " + TABLE + " WHERE email = $1")
                .execute(Tuple.of(email.toLowerCase()))
                .replaceWithVoid();
    }

    @Scheduled(every = "1h")
    void cleanupExpiredSessions() {
        client.preparedQuery("DELETE FROM " + TABLE + " WHERE expires_at <= NOW()")
            .execute()
            .subscribe().with(
                rows -> {},
                err -> {}
            );
        uploadGates.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue()));
        recordGates.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue()));
    }
}
