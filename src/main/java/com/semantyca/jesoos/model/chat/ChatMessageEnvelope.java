package com.semantyca.jesoos.model.chat;

import com.semantyca.core.model.cnst.MessageType;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

public record ChatMessageEnvelope(
        MessageType type,
        String id,
        String username,
        String content,
        long timestamp,
        String connectionId
) {
    public static ChatMessageEnvelope of(MessageType type, String username, String content, long timestamp, String connectionId) {
        return new ChatMessageEnvelope(type, UUID.randomUUID().toString(), username, content, timestamp, connectionId);
    }

    public JsonObject toJsonObject() {
        return new JsonObject()
                .put("type", "message")
                .put("data", new JsonObject()
                        .put("type", type.name())
                        .put("id", id)
                        .put("username", username)
                        .put("content", content)
                        .put("timestamp", timestamp)
                        .put("connectionId", connectionId)
                );
    }
}
