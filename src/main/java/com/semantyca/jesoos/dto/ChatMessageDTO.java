package com.semantyca.jesoos.dto;

import com.semantyca.core.model.cnst.MessageType;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

public class ChatMessageDTO {
    private final MessageType type;
    private final String content;
    private final String username;
    private final String connectionId;
    private final long timestamp;
    private final String id;
    private final Boolean asWrapper;

    private ChatMessageDTO(Builder builder) {
        this.type = builder.type;
        this.content = builder.content;
        this.username = builder.username;
        this.connectionId = builder.connectionId;
        this.timestamp = builder.timestamp != null ? builder.timestamp : System.currentTimeMillis();
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.asWrapper = builder.asWrapper;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder user(String content, String username, String connectionId) {
        return builder()
                .type(MessageType.USER)
                .content(content)
                .username(username)
                .connectionId(connectionId);
    }

    public static Builder bot(String content, String username, String connectionId) {
        return builder()
                .type(MessageType.BOT)
                .content(content)
                .username(username)
                .connectionId(connectionId);
    }

    public static Builder error(String content, String username, String connectionId) {
        return builder()
                .type(MessageType.ERROR)
                .content(content)
                .username(username)
                .connectionId(connectionId);
    }

    public static Builder processing(String content, String connectionId) {
        return builder()
                .type(MessageType.PROCESSING)
                .content(content)
                .username("system")
                .connectionId(connectionId);
    }

    public static Builder chunk(String content, String username, String connectionId) {
        return builder()
                .type(MessageType.CHUNK)
                .content(content)
                .username(username)
                .connectionId(connectionId);
    }

    public String toJson() {
        boolean shouldUseWrapper = asWrapper != null ? asWrapper : 
            (type == MessageType.USER || type == MessageType.BOT);

        if (shouldUseWrapper) {
            return new JsonObject()
                    .put("type", "message")
                    .put("data", new JsonObject()
                            .put("type", type.name())
                            .put("id", id)
                            .put("username", username)
                            .put("content", content)
                            .put("timestamp", timestamp)
                            .put("connectionId", connectionId)
                    ).encode();
        } else {
            JsonObject message = new JsonObject()
                    .put("type", type.name())
                    .put("username", username)
                    .put("connectionId", connectionId);

            if (type == MessageType.ERROR) {
                message.put("message", content);
            } else {
                message.put("content", content);
            }

            if (type != MessageType.PROCESSING) {
                message.put("timestamp", timestamp);
            }

            return message.encode();
        }
    }

    public static class Builder {
        private MessageType type;
        private String content;
        private String username;
        private String connectionId;
        private Long timestamp;
        private String id;
        private Boolean asWrapper;

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder connectionId(String connectionId) {
            this.connectionId = connectionId;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder asWrapper(boolean asWrapper) {
            this.asWrapper = asWrapper;
            return this;
        }

        public ChatMessageDTO build() {
            if (type == null) {
                throw new IllegalStateException("MessageType is required");
            }
            if (content == null) {
                throw new IllegalStateException("Content is required");
            }
            if (connectionId == null) {
                throw new IllegalStateException("ConnectionId is required");
            }
            if (username == null) {
                throw new IllegalStateException("Username is required");
            }
            return new ChatMessageDTO(this);
        }
    }
}
