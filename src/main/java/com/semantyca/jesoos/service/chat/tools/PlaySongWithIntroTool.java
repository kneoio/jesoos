package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class PlaySongWithIntroTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "The radio station slug name"),
                        "songId", Map.of(
                                "type", "string",
                                "description", "UUID of the song from search results"),
                        "textToTTSIntro", Map.of(
                                "type", "string",
                                "description", "DJ intro speech text"),
                        "priority", Map.of(
                                "type", "integer",
                                "description", "Priority: 10=LAST, 9=HIGH, 8=INTERRUPT, 7=HARD_INTERRUPT")
                )))
                .required(List.of("brandName", "songId", "textToTTSIntro"))
                .build();

        return Tool.builder()
                .name("play_song_with_intro")
                .description("Queue a song with custom DJ intro speech")
                .inputSchema(schema)
                .build();
    }
}
