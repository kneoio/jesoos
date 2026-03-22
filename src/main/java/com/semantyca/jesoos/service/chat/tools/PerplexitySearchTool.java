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
                                "description", "The Internet search query to send to Perplexity"))))
                .required(JsonValue.from(List.of("query")))
                .build();

        return Tool.builder()
                .name("perplexity_search")
                .description("Search the web using Perplexity to get current information, facts, news, " +
                        "or answer questions that require up-to-date knowledge")
                .inputSchema(schema)
                .build();
    }
}
