package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class ListGeneratorPromptsTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "Brand (radio station) slug name to list generator prompts for"))))
                .required(List.of("brandName"))
                .build();

        return Tool.builder()
                .name("list_generator_prompts")
                .description("List available content generator prompts (news, weather, etc.) for a radio station. Use this to discover what types of content can be generated before calling generate_content.")
                .inputSchema(schema)
                .build();
    }
}
