package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class PlayByCodeTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "play_by_code",
                "Queue a song by its play code. Use when the listener types a short code (one word or token, optional leading #). "
                        + "Works without sign-in. Do not search. Do not ask for a shout-out.",
                Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "The radio station slug name"),
                        "playCode", Map.of(
                                "type", "string",
                                "description", "The play code the listener typed, without a leading #")
                ),
                List.of("brandName", "playCode")
        );
    }
}
