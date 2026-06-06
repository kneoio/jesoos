package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class SendEmailToOwnerTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "inform_owner",
                "Send a message to the station owner. Use for listener feedback, complaints, compliments, suggestions, or any message the listener wants station management to see.",
                Map.of(
                        "subject", Map.of(
                                "type", "string",
                                "description", "Brief subject line"),
                        "message", Map.of(
                                "type", "string",
                                "description", "Full message body")
                ),
                List.of("subject", "message")
        );
    }
}
