package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class PerplexitySearchTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "query",
                        Map.of(
                                "type", "string",
                                "description", "The web search query. Use natural language to ask about current events, news, facts, or information not available in the radio platform."))))
                .required(JsonValue.from(List.of("query")))
                .build();

        return Tool.builder()
                .name("perplexity_search")
                .description("Search the internet for current information, news, facts, or general knowledge that is NOT about the radio station's music library. " +
                        "Use this for: weather, news, sports, celebrities, general facts, current events. " +
                        "Do NOT use this to search for songs in the station - use search_brand_sound_fragments for that.")
                .inputSchema(schema)
                .build();
    }
}
