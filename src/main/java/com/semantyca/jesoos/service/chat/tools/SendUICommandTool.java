package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class SendUICommandTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "send_ui_command",
                "Send a UI command to the chat frontend to trigger interactive elements. Call this when the user expresses intent that requires a UI action — e.g., when a verified artist says they want to upload a song, send show_upload_button to reveal the upload control.",
                Map.of(
                        "command", Map.of(
                                "type", "string",
                                "description", "Command name to send to UI. Supported: show_upload_button"),
                        "payload", Map.of(
                                "type", "object",
                                "description", "Optional payload data for the command")
                ),
                List.of("command")
        );
    }
}
