package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class SendEmailToOwnerTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "send_email",
                "Send an email. Use recipient='owner' to contact the station management (feedback, complaints, questions). Use recipient='self' to send something to the listener's own registered email (e.g. today's agenda, a playlist, a summary).",
                Map.of(
                        "recipient", Map.of(
                                "type", "string",
                                "enum", new String[]{"owner", "self"},
                                "description", "'owner' to send to the station owner, 'self' to send to the listener's own email"),
                        "subject", Map.of(
                                "type", "string",
                                "description", "Brief subject line"),
                        "message", Map.of(
                                "type", "string",
                                "description", "Full message body")
                ),
                List.of("recipient", "subject", "message")
        );
    }
}
