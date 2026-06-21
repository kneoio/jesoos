package com.semantyca.jesoos.messaging;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Setter
@Getter
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

}
