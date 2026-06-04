package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.live.scripting.PerplexitySearchHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class PerplexitySearchToolHandler extends BaseToolHandler {

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            PerplexitySearchHelper perplexitySearchHelper,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        PerplexitySearchToolHandler handler = new PerplexitySearchToolHandler();
        String query = (String) inputMap.getOrDefault("query", "");

        handler.sendProcessingChunk(chunkHandler, connectionId, "Searching web for: " + query);

        return perplexitySearchHelper.search(query)
                .flatMap((JsonObject searchResult) -> {
                    handler.sendProcessingChunk(chunkHandler, connectionId,
                            searchResult.containsKey("error") ? "Search failed: " + searchResult.getString("error") : "Search completed");
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, searchResult.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory))
                            .onFailure().invoke(err -> handler.sendBotChunk(chunkHandler, connectionId, "bot", "Failed to generate response: " + err.getMessage()));
                })
                .onFailure().recoverWithUni(err -> {
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I could not perform the search due to a technical issue.");
                    return Uni.createFrom().voidItem();
                });
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap, PerplexitySearchHelper perplexitySearchHelper) {
        String query = (String) inputMap.getOrDefault("query", "");
        return perplexitySearchHelper.search(query)
                .map(result -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(result.encode()))
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new io.vertx.core.json.JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
