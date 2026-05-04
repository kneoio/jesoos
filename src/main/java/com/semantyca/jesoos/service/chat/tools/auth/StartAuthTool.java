package com.semantyca.jesoos.service.chat.tools.auth;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class StartAuthTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "start_auth",
                "Start passwordless authentication by sending a one-time code to user email",
                Map.of(
                        "email", Map.of(
                                "type", "string",
                                "description", "User email address for authentication")
                ),
                List.of("email")
        );
    }
}
