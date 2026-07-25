package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class AssessTrackTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "assess_track",
                "Listen to an uploaded track BEFORE saving it. Runs audio analysis (spectra) on the temp "
                        + "file returned by /chat/upload-temp or import_from_suno and returns bpm, key/scale, moods, "
                        + "top_genres, danceability, loudness, duration_seconds, an is_music verdict, and a weak "
                        + "ai_generated_metadata_check. ALWAYS call this after a track is uploaded/imported and BEFORE "
                        + "upload_song. If is_music is false, the file is speech / spoken word / not a song - do NOT "
                        + "call upload_song; tell the artist you can only add music. If is_music is true, react like a "
                        + "DJ who just heard it (energy, mood, genre, tempo) before confirming the save.",
                Map.of(
                        "temp_filename", Map.of(
                                "type", "string",
                                "description", "Filename returned by the upload endpoint or import_from_suno")
                ),
                List.of("temp_filename")
        );
    }
}
