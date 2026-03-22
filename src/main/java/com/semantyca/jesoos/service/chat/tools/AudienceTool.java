package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class AudienceTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "search_term", Map.of(
                                "type", "string",
                                "description", "Search term to find listeners using PostgreSQL similarity search on keywords (optional, returns all if not provided, max 100 results)"),
                        "countries", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Filter listeners by country codes (optional, e.g., ['US', 'GB', 'DE'])")
                )))
                .build();

        return Tool.builder()
                .name("listener")
                .description("Search registered listeners using PostgreSQL similarity matching. Returns max 100 non-archived listeners ordered by relevance. Use search_term to find listeners by keywords. Optionally filter by countries.")
                .inputSchema(schema)
                .build();
    }
}
