package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class GetBrandCatalogSummary {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName",
                        Map.of("type", "string",
                                "description", "The radio station slug name (e.g., 'lumisonic'). Use the current station's slug from the context.")
                )))
                .required(List.of("brandName"))
                .build();

        return Tool.builder()
                .name("get_brand_catalog_summary")
                .description("Get a full catalog summary for a radio station: total track count, all distinct artists with how many songs each, and all genres with song counts. Use this for questions like 'what bands do you have?', 'what genres does this station play?', or any overview of the music catalog.")
                .inputSchema(schema)
                .build();
    }
}
