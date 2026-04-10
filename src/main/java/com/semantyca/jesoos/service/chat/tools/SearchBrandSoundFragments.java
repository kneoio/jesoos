package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class SearchBrandSoundFragments {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName",
                        Map.of(
                                "type", "string",
                                "description", "The radio station slug name (e.g., 'lumisonic', 'sunonation'). Use the current station's slug from the context."),
                        "keyword",
                        Map.of(
                                "type", "string",
                                "description", "Search keyword: artist name, song title, album name, or genre to find in the station's music library"),
                        "limit",
                        Map.of(
                                "type", "integer",
                                "description", "Max number of songs to return (default 10)"),
                        "offset",
                        Map.of(
                                "type", "integer",
                                "description", "Offset for pagination (default 0)")
                )))
                .required(List.of("brandName", "keyword"))
                .build();

        return Tool.builder()
                .name("search_brand_sound_fragments")
                .description("Search for songs in a specific radio station's music library by keyword. Use this when users ask about songs, artists, albums, or music available in the station. Searches by artist name, song title, album, or genre.")
                .inputSchema(schema)
                .build();
    }
}
