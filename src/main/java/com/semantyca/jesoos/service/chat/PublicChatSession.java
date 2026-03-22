package com.semantyca.jesoos.service.chat;

import java.io.Serializable;
import java.time.Instant;

public record PublicChatSession(String email, Instant expiresAt) implements Serializable {
    boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}