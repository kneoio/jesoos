package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class StartAuthTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "email",
                        Map.of(
                                "type", "string",
                                "description", "User email address for authentication")
                )))
                .required(List.of("email"))
                .build();

        return Tool.builder()
                .name("start_auth")
                .description("Start passwordless authentication by sending a one-time code to user email")
                .inputSchema(schema)
                .build();
    }
}
