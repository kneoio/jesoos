package com.semantyca.jesoos.service.ask.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public final class SearchPlatformKnowledgeTool {

    private SearchPlatformKnowledgeTool() {}

    public static LlmTool toTool() {
        return LlmTool.of(
                "search_platform_knowledge",
                "Search Mixpla platform knowledge (services, workflows, terminology). " +
                        "Use for how Mixpla works — not for live station actions.",
                Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Search query about Mixpla platform concepts or services")
                ),
                List.of("query")
        );
    }
}
