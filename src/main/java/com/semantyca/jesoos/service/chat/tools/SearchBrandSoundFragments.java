package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class SearchBrandSoundFragments {

    public static LlmTool toTool() {
        return LlmTool.of(
                "search_brand_sound_fragments",
                "Search or browse songs in a specific radio station's music library. Keyword is optional — omit it to list all songs. Supports genre and label filtering. Use get_station_music_metadata first to discover available genres and labels. Only searches within the station's own playlist, not the internet.",
                Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "The radio station slug name (e.g., 'lumisonic', 'sunonation'). Use the current station's slug from the context."),
                        "keyword", Map.of(
                                "type", "string",
                                "description", "Optional text search: artist name, song title, or album. Omit to browse all songs."),
                        "genres", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Optional genre names to filter by (e.g. ['Electronic', 'Pop']). Use get_station_music_metadata to see available genres."),
                        "labels", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Optional label names to filter by. Use get_station_music_metadata to see available labels."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Max number of songs to return (default 10)"),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Offset for pagination (default 0)")
                ),
                List.of("brandName")
        );
    }
}
