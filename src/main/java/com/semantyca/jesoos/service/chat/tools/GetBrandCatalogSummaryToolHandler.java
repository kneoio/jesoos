package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
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
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            AiHelperService aiHelperService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        GetBrandCatalogSummaryToolHandler handler = new GetBrandCatalogSummaryToolHandler();
        String brandName = inputMap.getOrDefault("brandName", JsonValue.from("")).toString().replace("\"", "");

        if (brandName.isEmpty()) {
            LOGGER.error("[CatalogSummary] brandName is required but was not provided");
            return Uni.createFrom().failure(new IllegalArgumentException("brandName is required"));
        }

        LOGGER.infof("[CatalogSummary] AI requested catalog summary for brand: '%s'", brandName);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Loading catalog summary...");

        return aiHelperService.getBrandCatalogSummaryForAi(brandName)
                .flatMap(summary -> {
                    LOGGER.infof("[CatalogSummary] Summary loaded for brand '%s': %d total tracks", brandName, summary.getLong("totalTracks"));

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, summary.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                }).onFailure().recoverWithUni(err -> {
                    LOGGER.errorf(err, "[CatalogSummary] Failed to load catalog summary for brand: %s", brandName);
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I could not retrieve the catalog summary right now.");
                    return Uni.createFrom().voidItem();
                });
    }
}
