package com.semantyca.jesoos.service.chat.tools.auth;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class VerifyCode {

    public static LlmTool toTool() {
        return LlmTool.of(
                "verify_code",
                "Verify OTP code and authenticate user. Pass preferred_name if user already shared their name.",
                Map.of(
                        "email", Map.of("type", "string"),
                        "code", Map.of("type", "string"),
                        "preferred_name", Map.of(
                                "type", "string",
                                "description", "User's name if already known from conversation")
                ),
                List.of("email", "code")
        );
    }
}
