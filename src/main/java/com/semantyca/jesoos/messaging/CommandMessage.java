package com.semantyca.jesoos.messaging;

import java.util.Map;
import java.util.UUID;

public class CommandMessage {

    private String type;
    private String source;
    private String target;
    private UUID correlationId;
    private long timestamp;
    private Map<String, Object> payload;

    public CommandMessage() {}

    public static CommandMessage of(String type, String source, String target, Map<String, Object> payload) {
        CommandMessage msg = new CommandMessage();
        msg.type = type;
        msg.source = source;
        msg.target = target;
        msg.correlationId = UUID.randomUUID();
        msg.timestamp = System.currentTimeMillis();
        msg.payload = payload;
        return msg;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public UUID getCorrelationId() { return correlationId; }
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
