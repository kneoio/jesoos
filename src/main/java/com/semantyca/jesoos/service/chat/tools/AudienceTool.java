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
                                "description", "Search by listener name, nickname, or profile information (optional, returns all listeners if not provided, max 100 results)"),
                        "countries", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Filter to show only listeners from specific countries using country codes (e.g., ['US', 'GB', 'FR', 'DE'])")
                )))
                .build();

        return Tool.builder()
                .name("listener")
                .description("View and search the radio station's audience (registered listeners). Use this to see who listens to the station, find specific listeners, or get audience demographics. Returns listener profiles with names and user data. Do NOT use this for the current user's personal data - use listener_data for that instead.")
                .inputSchema(schema)
                .build();
    }
}
