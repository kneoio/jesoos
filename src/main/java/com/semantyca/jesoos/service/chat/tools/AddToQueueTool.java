package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class AddToQueueTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "Brand (radio station) slug name to queue into"),
                        "textToTTSIntro", Map.of(
                                "type", "string",
                                "description", "Text to convert to speech for the intro"),
                        "soundFragments", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "description", "Optional map of fragment keys to UUIDs (as strings)"
                        ),
                        "priority", Map.of(
                                "type", "integer",
                                "enum", new Integer[]{7, 8, 9, 10},
                                "description", "Queue priority: 10=LAST (append to regular queue), 9=HIGH (play soon; clear prioritized queue), 8=INTERRUPT (interrupt current playlist), 7=HARD_INTERRUPT (immediately interrupt and play)."
                        )
                )))
                .build();

        return Tool.builder()
                .name("add_to_queue")
                .description("Queue audio for a brand using specified fragments, with optional priority control (7,8,9,10)")
                .inputSchema(schema)
                .build();
    }
}
