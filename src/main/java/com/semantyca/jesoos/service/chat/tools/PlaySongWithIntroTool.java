package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class PlaySongWithIntroTool {

    public static LlmTool toTool(String djLanguages) {
        String langHint = djLanguages != null && !djLanguages.isBlank()
                ? "MUST be written in: " + djLanguages + ". Never use the user's chat language."
                : "Must be written in the station's primary broadcast language, never the user's chat language.";
        return LlmTool.of(
                "play_song_with_intro",
                "Queue a song with custom DJ intro speech",
                Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "The radio station slug name"),
                        "songId", Map.of(
                                "type", "string",
                                "description", "UUID of the song from search results"),
                        "textToTTSIntro", Map.of(
                                "type", "string",
                                "description", "DJ intro speech text — " + langHint),
                        "priority", Map.of(
                                "type", "integer",
                                "description", "Priority: 8=normal, 7=high")
                ),
                List.of("brandName", "songId", "textToTTSIntro")
        );
    }
}
