package com.semantyca.jesoos.service.chat;

import java.time.Instant;

record UserToken(long userId, String username, Instant expiresAt) {

    boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
