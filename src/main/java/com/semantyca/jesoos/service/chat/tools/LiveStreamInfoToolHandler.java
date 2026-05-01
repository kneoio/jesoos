package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.service.PlaylistQueueService;
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
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            PlaylistQueueService playlistQueueService,
            String brandName,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        LiveStreamInfoToolHandler handler = new LiveStreamInfoToolHandler();
        String action = inputMap.getOrDefault("action", JsonValue.from("get_live_queue")).toString().replace("\"", "");

        LOGGER.infof("[LiveStreamInfo] action=%s, brand=%s", action, brandName);

        return playlistQueueService.getQueueByBrandSlug(brandName)
                .flatMap(queue -> {
                    JsonObject payload = new JsonObject().put("ok", true).put("queue", queue);
                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[LiveStreamInfo] get_live_queue failed for brand={}", brandName, err);
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I couldn't get the queue right now, please try again.");
                    return Uni.createFrom().voidItem();
                });
    }
}
