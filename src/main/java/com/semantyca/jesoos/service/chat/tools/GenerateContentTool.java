package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class GenerateContentTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "Brand (radio station) slug name to generate content for"),
                        "promptId", Map.of(
                                "type", "string",
                                "description", "UUID of the generator prompt to use (obtained from list_generator_prompts)"),
                        "priority", Map.of(
                                "type", "integer",
                                "enum", new Integer[]{8, 9, 10},
                                "description", "Queue priority: 10=REGULAR, 9=HIGH, 8=URGENT"))))
                .required(List.of("brandName", "promptId"))
                .build();

        return Tool.builder()
                .name("generate_content")
                .description("Generate content (news, weather, etc.) using a specific generator prompt and queue it directly to air, bypassing the scene schedule. Use list_generator_prompts first to discover available prompts.")
                .inputSchema(schema)
                .build();
    }
}
