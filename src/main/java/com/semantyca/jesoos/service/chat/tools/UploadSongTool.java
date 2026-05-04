package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class UploadSongTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "upload_song",
                "After POST /chat/upload-temp returns a filename: save that file to the catalog and queue it for broadcast with a spoken DJ intro (TTS). Eligibility = listener has the station artist label (see listener_data get → has_artist_label). This is NOT email verification or sign-in — never confuse with account verification.",
                Map.of(
                        "temp_filename", Map.of(
                                "type", "string",
                                "description", "Filename returned by the upload endpoint"),
                        "title", Map.of(
                                "type", "string",
                                "description", "Song title"),
                        "artist", Map.of(
                                "type", "string",
                                "description", "Artist name"),
                        "genre_names", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "List of genre names (must match available genres)"),
                        "description", Map.of(
                                "type", "string",
                                "description", "Optional artist note about the song"),
                        "intro_text", Map.of(
                                "type", "string",
                                "description", "Exact words you will speak on air as the DJ (TTS) before the track — not 'written copy for the website'. DJ language, warm, natural.")
                ),
                List.of("temp_filename", "title", "artist", "genre_names", "intro_text")
        );
    }
}
