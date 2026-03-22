package com.semantyca.jesoos.service.chat;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PublicChatTokenService {
    private static final long TOKEN_EXPIRY_SECONDS = 86400 * 30;
    private final Map<String, UserToken> tokens = new ConcurrentHashMap<>();

    public String generateToken(long userId, String username) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, new UserToken(userId, username, Instant.now().plusSeconds(TOKEN_EXPIRY_SECONDS)));
        return token;
    }

    public TokenValidationResult validateToken(String token) {
        UserToken userToken = tokens.get(token);
        if (userToken == null || userToken.isExpired()) {
            if (userToken != null) {
                tokens.remove(token);
            }
            return new TokenValidationResult(false, null, "Invalid or expired token");
        }
        return new TokenValidationResult(true, userToken.userId(), null);
    }

    public record TokenValidationResult(boolean valid, Long userId, String error) {}
}
