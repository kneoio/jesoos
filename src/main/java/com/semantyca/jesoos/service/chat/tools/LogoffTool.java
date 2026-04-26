package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class LogoffTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of()))
                .build();

        return Tool.builder()
                .name("logoff")
                .description("Log out the current authenticated user and invalidate the session token")
                .inputSchema(schema)
                .build();
    }
}
