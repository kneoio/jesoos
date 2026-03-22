package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class SendEmailToOwnerTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "subject", Map.of(
                                "type", "string",
                                "description", "Email subject line (required)"),
                        "message", Map.of(
                                "type", "string",
                                "description", "Email message content - the complaint, feedback, or message the user wants to send to the owner (required)")
                )))
                .required(List.of("subject", "message"))
                .build();

        return Tool.builder()
                .name("send_email_to_owner")
                .description("Send an email message to the radio station owner. Use this when a user wants to complain about something, send feedback, report an issue, or send any message to the station owner. The email will be sent from the user's registered email address to the owner.")
                .inputSchema(schema)
                .build();
    }
}
