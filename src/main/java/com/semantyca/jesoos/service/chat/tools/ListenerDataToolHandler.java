package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.UserData;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.mixpla.model.Listener;
import com.semantyca.jesoos.service.ListenerService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class ListenerDataToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerDataToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            String stationSlug,
            long userId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        ListenerDataToolHandler handler = new ListenerDataToolHandler();
        String action = inputMap.getOrDefault("action", JsonValue.from("get")).toString().replace("\"", "");
        String fieldName = inputMap.getOrDefault("field_name", JsonValue.from("")).toString().replace("\"", "");
        String fieldValue = inputMap.getOrDefault("field_value", JsonValue.from("")).toString().replace("\"", "");

        LOGGER.info("[ListenerData] Action: {}, fieldName: {}, userId: {}, connectionId: {}",
                action, fieldName, userId, connectionId);

        return listenerService.getByUserId(userId)
                .flatMap(listener -> {
                    if (listener == null) {
                        return handleError(toolUse, "Listener not found. User must be registered first.", handler, conversationHistory, systemPromptCall2, streamFn);
                    }

                    return switch (action) {
                        case "get" ->
                                handleGet(toolUse, listener, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "set" ->
                                handleSet(toolUse, listener, fieldName, fieldValue, listenerService, stationSlug, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "remove" ->
                                handleRemove(toolUse, listener, fieldName, listenerService, stationSlug, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        default ->
                                handleError(toolUse, "Invalid action: " + action, handler, conversationHistory, systemPromptCall2, streamFn);
                    };
                });
    }

    private static Uni<Void> handleGet(
            ToolUseBlock toolUse,
            Listener listener,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        handler.sendProcessingChunk(chunkHandler, connectionId, "Retrieving listener data...");

        JsonObject payload = new JsonObject()
                .put("ok", true)
                .put("listener_id", listener.getId().toString())
                .put("user_id", listener.getUserId())
                .put("localized_name", JsonObject.mapFrom(listener.getLocalizedName()))
                .put("nick_name", JsonObject.mapFrom(listener.getNickName()))
                .put("user_data", listener.getUserData() != null ? JsonObject.mapFrom(listener.getUserData().getData()) : new JsonObject());

        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

        MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(secondCallParams);
    }

    private static Uni<Void> handleSet(
            ToolUseBlock toolUse,
            Listener listener,
            String fieldName,
            String fieldValue,
            ListenerService listenerService,
            String stationSlug,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        if (fieldName.isEmpty() || fieldValue.isEmpty()) {
            return handleError(toolUse, "field_name and field_value are required for 'set' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Storing user data...");

        if (listener.getUserData() == null) {
            listener.setUserData(new UserData(new HashMap<>()));
        }
        listener.getUserData().getData().put(fieldName, fieldValue);

        return listenerService.update(listener.getId(), listener, stationSlug)
                .flatMap(updatedListener -> {
                    LOGGER.info("[ListenerData] Set field '{}' = '{}' for listener {}", fieldName, fieldValue, listener.getId());

                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("action", "set")
                            .put("field_name", fieldName)
                            .put("field_value", fieldValue)
                            .put("message", "User data stored successfully");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListenerData] Failed to set field", err);
                    return handleError(toolUse, "Failed to store user data: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleRemove(
            ToolUseBlock toolUse,
            Listener listener,
            String fieldName,
            ListenerService listenerService,
            String stationSlug,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        if (fieldName.isEmpty()) {
            return handleError(toolUse, "field_name is required for 'remove' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Removing user data...");

        boolean removed = false;
        if (listener.getUserData() != null) {
            removed = listener.getUserData().getData().remove(fieldName) != null;
        }

        if (!removed) {
            JsonObject payload = new JsonObject()
                    .put("ok", true)
                    .put("action", "remove")
                    .put("field_name", fieldName)
                    .put("removed", false)
                    .put("message", "Field not found");

            handler.addToolUseToHistory(toolUse, conversationHistory);
            handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

            MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
            return streamFn.apply(secondCallParams);
        }

        return listenerService.update(listener.getId(), listener, stationSlug)
                .flatMap(updatedListener -> {
                    LOGGER.info("[ListenerData] Removed field '{}' for listener {}", fieldName, listener.getId());

                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("action", "remove")
                            .put("field_name", fieldName)
                            .put("removed", true)
                            .put("message", "User data removed successfully");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListenerData] Failed to remove field", err);
                    return handleError(toolUse, "Failed to remove user data: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(
            ToolUseBlock toolUse,
            String errorMessage,
            ListenerDataToolHandler handler,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        JsonObject errorPayload = new JsonObject()
                .put("ok", false)
                .put("error", errorMessage);

        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

        MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(secondCallParams);
    }
}
