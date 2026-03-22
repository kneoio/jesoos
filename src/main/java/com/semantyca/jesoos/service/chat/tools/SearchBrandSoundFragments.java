package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class SearchBrandSoundFragments {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName",
                        Map.of(
                                "type", "string",
                                "description", "Brand (radio station) name to search within"),
                        "keyword",
                        Map.of(
                                "type", "string",
                                "description", "Free-text keyword to search in fragment metadata"),
                        "limit",
                        Map.of(
                                "type", "integer",
                                "description", "Max number of items to return (default 50)"),
                        "offset",
                        Map.of(
                                "type", "integer",
                                "description", "Offset for pagination (default 0)")
                )))
                .build();

        return Tool.builder()
                .name("search_brand_sound_fragments")
                .description("Search brand sound fragments by keyword for a specific brand")
                .inputSchema(schema)
                .build();
    }
}
