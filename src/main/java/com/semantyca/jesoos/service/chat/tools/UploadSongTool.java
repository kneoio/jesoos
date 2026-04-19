package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class UploadSongTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
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
                                "description", "DJ intro speech for on-air broadcast — must be in DJ language, warm and natural")
                )))
                .required(List.of("temp_filename", "title", "artist", "genre_names", "intro_text"))
                .build();

        return Tool.builder()
                .name("upload_song")
                .description("Save an artist-uploaded song to the station catalog and immediately queue it for broadcast with a DJ intro. Only available to verified artists (is_artist=true in listener data).")
                .inputSchema(schema)
                .build();
    }
}
