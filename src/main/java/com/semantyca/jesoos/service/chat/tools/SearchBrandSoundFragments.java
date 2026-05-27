package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class SearchBrandSoundFragments {

    public static LlmTool toTool() {
        return LlmTool.of(
                "search_brand_sound_fragments",
                "Search or browse songs in a specific radio station's music library. Include genre or label names directly in the keyword — the search covers title, artist, album, genres, and labels. Omit keyword to list all songs. Only searches within the station's own playlist, not the internet.",
                Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "The radio station slug name (e.g., 'lumisonic', 'sunonation'). Use the current station's slug from the context."),
                        "keyword", Map.of(
                                "type", "string",
                                "description", "Optional text search: artist name, song title, album, genre, or label. Multiple terms are combined (e.g. 'electronic dancefloor'). Omit to browse all songs."),
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
