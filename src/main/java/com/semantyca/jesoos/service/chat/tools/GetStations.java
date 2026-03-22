package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class GetStations {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "country",
                        Map.of(
                                "type", "string",
                                "description", "Filter stations by country code (e.g., 'US', 'UK')"),
                        "query",
                        Map.of(
                                "type", "string",
                                "description", "Search query to filter stations by name"))))
                .build();

        return Tool.builder()
                .name("get_stations")
                .description("Get a list of available radio stations with their online/offline status")
                .inputSchema(schema)
                .build();
    }
}
