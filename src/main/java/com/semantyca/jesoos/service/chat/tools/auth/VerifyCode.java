package com.semantyca.jesoos.service.chat.tools.auth;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.util.List;
import java.util.Map;


public class VerifyCode {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "email",
                        Map.of("type", "string"),
                        "code",
                        Map.of("type", "string"),
                        "preferred_name",
                        Map.of("type", "string", "description", "User's name if already known from conversation")
                )))
                .required(List.of("email", "code"))
                .build();

        return Tool.builder()
                .name("verify_code")
                .description("Verify OTP code and authenticate user. Pass preferred_name if user already shared their name.")
                .inputSchema(schema)
                .build();
    }
}
