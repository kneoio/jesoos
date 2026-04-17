package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class GetStationMusicMetadata {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName",
                        Map.of("type", "string",
                                "description", "The radio station slug name to get metadata for.")
                )))
                .required(List.of("brandName"))
                .build();

        return Tool.builder()
                .name("get_station_music_metadata")
                .description("Returns available genres and labels for a radio station's music library. Call this before using search_brand_sound_fragments with genre or label filters, so you know the valid filter values.")
                .inputSchema(schema)
                .build();
    }
}
