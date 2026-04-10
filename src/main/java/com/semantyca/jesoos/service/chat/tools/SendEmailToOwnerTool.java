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
                                "description", "Brief subject line summarizing the message (e.g., 'Feedback about music selection', 'Technical issue with stream')"),
                        "message", Map.of(
                                "type", "string",
                                "description", "The full message content from the user - their complaint, feedback, question, or request to send to the station owner")
                )))
                .required(List.of("subject", "message"))
                .build();

        return Tool.builder()
                .name("send_email_to_owner")
                .description("Send an email to the radio station owner. Use when a user wants to: send feedback, report an issue, make a complaint, ask a question, or contact the station management. The email will be sent from the user's registered email to the owner.")
                .inputSchema(schema)
                .build();
    }
}
