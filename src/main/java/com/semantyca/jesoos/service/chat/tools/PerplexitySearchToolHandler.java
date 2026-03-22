package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.service.live.scripting.PerplexitySearchHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class PerplexitySearchToolHandler extends BaseToolHandler {

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            PerplexitySearchHelper perplexitySearchHelper,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        PerplexitySearchToolHandler handler = new PerplexitySearchToolHandler();
        String query = inputMap.getOrDefault("query", JsonValue.from("")).toString().replace("\"", "");

        handler.sendProcessingChunk(chunkHandler, connectionId, "Searching web for: " + query);

        return perplexitySearchHelper.search(query)
                .flatMap((JsonObject searchResult) -> {
                    if (searchResult.containsKey("error")) {
                        handler.sendProcessingChunk(chunkHandler, connectionId, "Search failed: " + searchResult.getString("error"));
                        handler.addToolUseToHistory(toolUse, conversationHistory);
                        handler.addToolResultToHistory(toolUse, searchResult.encode(), conversationHistory);
                    } else {
                        handler.sendProcessingChunk(chunkHandler, connectionId, "Search completed");
                        handler.addToolUseToHistory(toolUse, conversationHistory);
                        handler.addToolResultToHistory(toolUse, searchResult.encode(), conversationHistory);
                    }

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams)
                            .onFailure().invoke(err -> {
                                System.err.println("StreamFn failed in PerplexitySearchToolHandler: " + err.getMessage());
                                err.printStackTrace();
                                handler.sendBotChunk(chunkHandler, connectionId, "bot", "Failed to generate response: " + err.getMessage());
                            });
                })
                .onFailure().recoverWithUni(err -> {
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I could not perform the search due to a technical issue.");
                    return Uni.createFrom().voidItem();
                });
    }
}
