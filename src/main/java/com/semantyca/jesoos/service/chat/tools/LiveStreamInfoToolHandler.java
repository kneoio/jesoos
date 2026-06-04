package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.PlaylistQueueService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class LiveStreamInfoToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(LiveStreamInfoToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            PlaylistQueueService playlistQueueService,
            String brandName,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        LiveStreamInfoToolHandler handler = new LiveStreamInfoToolHandler();
        String action = (String) inputMap.getOrDefault("action", "get_live_queue");

        LOGGER.infof("[LiveStreamInfo] action=%s, brand=%s", action, brandName);

        return playlistQueueService.getQueueByBrandSlug(brandName)
                .flatMap(queue -> {
                    JsonObject payload = new JsonObject().put("ok", true).put("queue", queue);
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[LiveStreamInfo] get_live_queue failed for brand={}", brandName, err);
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I couldn't get the queue right now, please try again.");
                    return Uni.createFrom().voidItem();
                });
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap, PlaylistQueueService playlistQueueService, String brandName) {
        return playlistQueueService.getQueueByBrandSlug(brandName)
                .map(queue -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", true).put("queue", queue).encode()))
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
