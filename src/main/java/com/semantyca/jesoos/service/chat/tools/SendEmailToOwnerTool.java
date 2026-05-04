package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class SendEmailToOwnerTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "send_email_to_owner",
                "Send an email to the radio station owner. Use when a user wants to: send feedback, report an issue, make a complaint, ask a question, or contact the station management. The email will be sent from the user's registered email to the owner.",
                Map.of(
                        "subject", Map.of(
                                "type", "string",
                                "description", "Brief subject line summarizing the message (e.g., 'Feedback about music selection', 'Technical issue with stream')"),
                        "message", Map.of(
                                "type", "string",
                                "description", "The full message content from the user - their complaint, feedback, question, or request to send to the station owner")
                ),
                List.of("subject", "message")
        );
    }
}
