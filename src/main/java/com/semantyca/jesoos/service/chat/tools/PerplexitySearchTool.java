package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class PerplexitySearchTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "perplexity_search",
                "Search the internet for current information, news, facts, or general knowledge that is NOT about the radio station's music library. " +
                        "Use this for: weather, news, sports, celebrities, general facts, current events. " +
                        "Do NOT use this to search for songs in the station - use search_brand_sound_fragments for that.",
                Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "The web search query. Use natural language to ask about current events, news, facts, or information not available in the radio platform.")
                ),
                List.of("query")
        );
    }
}
