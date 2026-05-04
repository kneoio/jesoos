package com.semantyca.jesoos.service.chat.tools.auth;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.Map;

public class LogoffTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "logoff",
                "Log out the current authenticated user and invalidate the session token",
                Map.of()
        );
    }
}
