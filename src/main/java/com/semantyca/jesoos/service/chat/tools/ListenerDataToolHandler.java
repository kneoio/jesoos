package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.UserData;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.mixpla.model.Listener;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class ListenerDataToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(ListenerDataToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            ListenerLabelCache labelCache,
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
        String labelIdentifier = inputMap.getOrDefault("label_identifier", JsonValue.from("")).toString().replace("\"", "");

        LOGGER.infof("[ListenerData] Action: %s, fieldName: %s, userId: %s, connectionId: %s",
                action, fieldName, userId, connectionId);

        return listenerService.getByUserId(userId)
                .flatMap(listener -> {
                    if (listener == null) {
                        return handleError(toolUse, "Listener not found. User must be registered first.", handler, conversationHistory, systemPromptCall2, streamFn);
                    }

                    return switch (action) {
                        case "get" ->
                                handleGet(toolUse, listener, labelCache, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "set" ->
                                handleSet(toolUse, listener, fieldName, fieldValue, listenerService, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "remove" ->
                                handleRemove(toolUse, listener, fieldName, listenerService, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "add_label" ->
                                handleAddLabel(toolUse, listener, labelIdentifier, listenerService, labelCache, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "remove_label" ->
                                handleRemoveLabel(toolUse, listener, labelIdentifier, listenerService, labelCache, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        default ->
                                handleError(toolUse, "Invalid action: " + action, handler, conversationHistory, systemPromptCall2, streamFn);
                    };
                });
    }

    private static Uni<Void> handleGet(
            ToolUseBlock toolUse,
            Listener listener,
            ListenerLabelCache labelCache,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        handler.sendProcessingChunk(chunkHandler, connectionId, "Remembering user...");

        return labelCache.getOrLoad("artist")
                .ifNoItem().after(java.time.Duration.ofSeconds(10)).fail()
                .onFailure().recoverWithItem(err -> {
                    LOGGER.warnf("[ListenerData] labelCache.getOrLoad timed out or failed: %s", err.getMessage());
                    return null;
                })
                .flatMap(artistLabelId -> {
        boolean hasArtistLabel = artistLabelId != null
                && listener.getLabels() != null
                && listener.getLabels().contains(artistLabelId);
        LOGGER.infof("[ListenerData] get: artistLabelId=%s, listenerLabels=%s, hasArtistLabel=%s",
                artistLabelId, listener.getLabels(), hasArtistLabel);

        JsonObject payload = new JsonObject()
                .put("ok", true)
                .put("listener_id", listener.getId().toString())
                .put("user_id", listener.getUserId())
                .put("localized_name", JsonObject.mapFrom(listener.getLocalizedName()))
                .put("nick_name", JsonObject.mapFrom(listener.getNickName()))
                .put("user_data", listener.getUserData() != null ? JsonObject.mapFrom(listener.getUserData().getData()) : new JsonObject())
                .put("labels", listener.getLabels() != null ? listener.getLabels().stream().map(Object::toString).collect(java.util.stream.Collectors.toList()) : List.of())
                .put("has_artist_label", hasArtistLabel);

        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

        MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(secondCallParams);
        });
    }

    private static Uni<Void> handleSet(
            ToolUseBlock toolUse,
            Listener listener,
            String fieldName,
            String fieldValue,
            ListenerService listenerService,
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
            LOGGER.infof("[ListenerData] userData was null, creating new for listener %s", listener.getId());
            listener.setUserData(new UserData(new HashMap<>()));
        }
        LOGGER.infof("[ListenerData] userData before set: %s", listener.getUserData().getData());
        listener.getUserData().put(fieldName, fieldValue);
        LOGGER.infof("[ListenerData] userData after set: %s", listener.getUserData().getData());

        return listenerService.updateUserData(listener.getId(), listener.getUserData())
                .flatMap(ignored -> {
                    LOGGER.infof("[ListenerData] Set field '%s' = '%s' for listener %s", fieldName, fieldValue, listener.getId());

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

        return listenerService.updateUserData(listener.getId(), listener.getUserData())
                .flatMap(ignored -> {
                    LOGGER.infof("[ListenerData] Removed field '%s' for listener %s", fieldName, listener.getId());

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

    private static Uni<Void> handleAddLabel(
            ToolUseBlock toolUse,
            Listener listener,
            String labelIdentifier,
            ListenerService listenerService,
            ListenerLabelCache labelCache,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        if (labelIdentifier.isEmpty()) {
            return handleError(toolUse, "label_identifier is required for 'add_label' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        return labelCache.getOrLoad(labelIdentifier).flatMap(labelId -> {
        if (labelId == null) {
            LOGGER.warnf("[ListenerData] add_label: label identifier '%s' not found in cache or DB", labelIdentifier);
            return handleError(toolUse, "Unknown label: " + labelIdentifier, handler, conversationHistory, systemPromptCall2, streamFn);
        }

        List<java.util.UUID> labels = listener.getLabels();
        LOGGER.infof("[ListenerData] add_label: resolved label '%s' -> %s, listener labels: %s", labelIdentifier, labelId, labels);
        if (labels.contains(labelId)) {
            LOGGER.infof("[ListenerData] Label '%s' already present for listener %s, skipping update", labelIdentifier, listener.getId());
            JsonObject payload = new JsonObject()
                    .put("ok", true)
                    .put("action", "add_label")
                    .put("label_identifier", labelIdentifier)
                    .put("already_had_label", true)
                    .put("message", "Listener already has this label");
            handler.addToolUseToHistory(toolUse, conversationHistory);
            handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
            return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Adding label...");

        labels.add(labelId);
        return listenerService.updateLabels(listener.getId(), labels)
                .flatMap(ignored -> {
                    LOGGER.infof("[ListenerData] Added label '%s' for listener %s", labelIdentifier, listener.getId());
                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("action", "add_label")
                            .put("label_identifier", labelIdentifier)
                            .put("already_had_label", false)
                            .put("message", "Label added successfully");
                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListenerData] Failed to add label", err);
                    return handleError(toolUse, "Failed to add label: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
        });
    }

    private static Uni<Void> handleRemoveLabel(
            ToolUseBlock toolUse,
            Listener listener,
            String labelIdentifier,
            ListenerService listenerService,
            ListenerLabelCache labelCache,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        if (labelIdentifier.isEmpty()) {
            return handleError(toolUse, "label_identifier is required for 'remove_label' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        java.util.UUID labelId = labelCache.get(labelIdentifier);
        if (labelId == null) {
            return handleError(toolUse, "Unknown label: " + labelIdentifier, handler, conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Removing label...");

        boolean removed = listener.getLabels().remove(labelId);
        if (!removed) {
            JsonObject payload = new JsonObject()
                    .put("ok", true)
                    .put("action", "remove_label")
                    .put("label_identifier", labelIdentifier)
                    .put("removed", false)
                    .put("message", "Label was not assigned");
            handler.addToolUseToHistory(toolUse, conversationHistory);
            handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
            return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
        }
        return listenerService.updateLabels(listener.getId(), listener.getLabels())
                .flatMap(ignored -> {
                    LOGGER.infof("[ListenerData] Removed label '%s' for listener %s", labelIdentifier, listener.getId());
                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("action", "remove_label")
                            .put("label_identifier", labelIdentifier)
                            .put("removed", true)
                            .put("message", "Label removed successfully");
                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListenerData] Failed to remove label", err);
                    return handleError(toolUse, "Failed to remove label: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
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
