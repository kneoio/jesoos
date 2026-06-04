package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class GetBrandCatalogSummaryToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(GetBrandCatalogSummaryToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            AiHelperService aiHelperService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        GetBrandCatalogSummaryToolHandler handler = new GetBrandCatalogSummaryToolHandler();
        String brandName = (String) inputMap.getOrDefault("brandName", "");

        if (brandName.isEmpty()) {
            LOGGER.error("[CatalogSummary] brandName is required but was not provided");
            return Uni.createFrom().failure(new IllegalArgumentException("brandName is required"));
        }

        LOGGER.infof("[CatalogSummary] AI requested catalog summary for brand: '%s'", brandName);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Loading catalog summary...");

        return aiHelperService.getBrandCatalogSummaryForAi(brandName)
                .flatMap(summary -> {
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, summary.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                }).onFailure().recoverWithUni(err -> {
                    LOGGER.errorf(err, "[CatalogSummary] Failed to load catalog summary for brand: %s", brandName);
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I could not retrieve the catalog summary right now.");
                    return Uni.createFrom().voidItem();
                });
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap, AiHelperService aiHelperService) {
        String brandName = (String) inputMap.getOrDefault("brandName", "");
        if (brandName.isEmpty()) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new io.vertx.core.json.JsonObject().put("ok", false).put("error", "brandName is required").encode()));
        }
        return aiHelperService.getBrandCatalogSummaryForAi(brandName)
                .map(summary -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(summary.encode()))
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new io.vertx.core.json.JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
