package com.semantyca.jesoos.service.chat.tools.ad;

import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.chat.ad.AdGraph;
import com.semantyca.jesoos.service.chat.ad.AdSessionData;
import com.semantyca.jesoos.service.chat.ad.AdSessionManager;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.chat.tools.BaseToolHandler;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class CreateAdToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateAdToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            BrandService brandService,
            AdSessionManager adSessionManager,
            AdGraph adGraph,
            long userId,
            String brandName,
            String djName,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        CreateAdToolHandler handler = new CreateAdToolHandler();
        handler.sendProcessingChunk(chunkHandler, connectionId, "Setting up your ad...");

        return brandService.getBySlugName(brandName)
                .flatMap(brand -> {
                    if (Boolean.FALSE.equals(brand.getChatFeatureFlags().get("CREATE_AD"))) {
                        JsonObject payload = new JsonObject().put("ok", false).put("error", "Ads are not enabled for this station");
                        handler.addToolUseToHistory(toolCall, conversationHistory);
                        handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                    }

                    AdSessionData session = new AdSessionData(
                            brandName, brand.getId(), userId);
                    session.setDjName(djName);
                    adSessionManager.start(connectionId, session);

                    return adGraph.generateFirstQuestion(session)
                            .flatMap(firstQuestion -> {
                                JsonObject payload = new JsonObject()
                                        .put("action", "collect_ad_details")
                                        .put("firstQuestion", firstQuestion);
                                handler.addToolUseToHistory(toolCall, conversationHistory);
                                handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                                return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                            });
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[CreateAd] failed", err);
                    return handler.handleError(toolCall, err.getMessage(), conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private Uni<Void> handleError(LlmToolCall toolCall, String message,
                                   List<LlmMessage> conversationHistory, String systemPromptCall2,
                                   Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject payload = new JsonObject().put("ok", false).put("error", message);
        addToolUseToHistory(toolCall, conversationHistory);
        addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
        return streamFn.apply(buildFollowUpParams(systemPromptCall2, conversationHistory));
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap, BrandService brandService,
            AdSessionManager adSessionManager, AdGraph adGraph,
            long userId, String brandName, String djName, String connectionId) {
        return brandService.getBySlugName(brandName)
                .chain(brand -> {
                    if (Boolean.FALSE.equals(brand.getChatFeatureFlags().get("CREATE_AD"))) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Ads are not enabled for this station").encode()));
                    }
                    AdSessionData session = new AdSessionData(brandName, brand.getId(), userId);
                    session.setDjName(djName);
                    adSessionManager.start(connectionId, session);
                    return adGraph.generateFirstQuestion(session)
                            .map(firstQuestion -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                    new JsonObject().put("action", "collect_ad_details")
                                            .put("firstQuestion", firstQuestion).encode()));
                })
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
