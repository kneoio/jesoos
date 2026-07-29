package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.UserData;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
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

    /** The only labels a chat agent may attach or detach. Everything else is admin-assigned. */
    private static final java.util.Set<String> ASSIGNABLE_LABELS = java.util.Set.of("artist");

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            ListenerService listenerService,
            ListenerLabelCache labelCache,
            long userId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        ListenerDataToolHandler handler = new ListenerDataToolHandler();
        String action = (String) inputMap.getOrDefault("action", "get");
        String fieldName = (String) inputMap.getOrDefault("field_name", "");
        String fieldValue = (String) inputMap.getOrDefault("field_value", "");
        String labelIdentifier = (String) inputMap.getOrDefault("label_identifier", "");

        LOGGER.infof("[ListenerData] Action: %s, fieldName: %s, userId: %s, connectionId: %s", action, fieldName, userId, connectionId);

        return listenerService.getByUserId(userId)
                .flatMap(listener -> {
                    if (listener == null) {
                        return handleError(toolCall, "Listener not found. User must be registered first.", handler, conversationHistory, systemPromptCall2, streamFn);
                    }
                    return switch (action) {
                        case "get" -> handleGet(toolCall, listener, labelCache, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "set" -> handleSet(toolCall, listener, fieldName, fieldValue, listenerService, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "remove" -> handleRemove(toolCall, listener, fieldName, listenerService, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "add_label" -> handleAddLabel(toolCall, listener, labelIdentifier, listenerService, labelCache, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        case "remove_label" -> handleRemoveLabel(toolCall, listener, labelIdentifier, listenerService, labelCache, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                        default -> handleError(toolCall, "Invalid action: " + action, handler, conversationHistory, systemPromptCall2, streamFn);
                    };
                });
    }

    private static Uni<Void> handleGet(LlmToolCall toolCall, Listener listener, ListenerLabelCache labelCache,
                                       ListenerDataToolHandler handler, Consumer<String> chunkHandler, String connectionId,
                                       List<LlmMessage> conversationHistory, String systemPromptCall2, Function<LlmRequest, Uni<Void>> streamFn) {
        handler.sendProcessingChunk(chunkHandler, connectionId, "Remembering user...");
        return labelCache.getOrLoad("artist")
                .ifNoItem().after(java.time.Duration.ofSeconds(10)).fail()
                .onFailure().recoverWithItem(err -> {
                    LOGGER.warnf("[ListenerData] labelCache.getOrLoad timed out or failed: %s", err.getMessage());
                    return null;
                })
                .flatMap(ignored -> {
                    List<String> resolvedLabels = labelCache.resolveToIdentifiers(listener.getLabels());
                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("listener_id", listener.getId().toString())
                            .put("user_id", listener.getUserId())
                            .put("localized_name", JsonObject.mapFrom(listener.getLocalizedName()))
                            .put("nick_name", JsonObject.mapFrom(listener.getNickName()))
                            .put("user_data", listener.getUserData() != null ? JsonObject.mapFrom(listener.getUserData().getData()) : new JsonObject())
                            .put("labels", resolvedLabels);
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                });
    }

    private static String sanitizeUserDataValue(String value) {
        if (value == null) return null;
        return value
                .replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("\\{\\{.*?}}", "")
                .replaceAll("[\\x00-\\x1F\\x7F]", "")
                .trim();
    }

    private static Uni<Void> handleSet(LlmToolCall toolCall, Listener listener, String fieldName, String fieldValue,
                                       ListenerService listenerService, ListenerDataToolHandler handler,
                                       Consumer<String> chunkHandler, String connectionId,
                                       List<LlmMessage> conversationHistory, String systemPromptCall2, Function<LlmRequest, Uni<Void>> streamFn) {
        if (fieldName.isEmpty() || fieldValue.isEmpty()) {
            return handleError(toolCall, "field_name and field_value are required for 'set' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }
        String sanitizedValue = sanitizeUserDataValue(fieldValue);
        if (sanitizedValue == null || sanitizedValue.isEmpty()) {
            return handleError(toolCall, "field_value is empty after sanitization", handler, conversationHistory, systemPromptCall2, streamFn);
        }
        handler.sendProcessingChunk(chunkHandler, connectionId, "Storing user data...");
        if (listener.getUserData() == null) listener.setUserData(new UserData(new HashMap<>()));
        listener.getUserData().put(fieldName, sanitizedValue);
        return listenerService.updateUserData(listener.getId(), listener.getUserData())
                .flatMap(ignored -> {
                    JsonObject payload = new JsonObject().put("ok", true).put("action", "set")
                            .put("field_name", fieldName).put("field_value", fieldValue).put("message", "User data stored successfully");
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> handleError(toolCall, "Failed to store user data: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn));
    }

    private static Uni<Void> handleRemove(LlmToolCall toolCall, Listener listener, String fieldName,
                                          ListenerService listenerService, ListenerDataToolHandler handler,
                                          Consumer<String> chunkHandler, String connectionId,
                                          List<LlmMessage> conversationHistory, String systemPromptCall2, Function<LlmRequest, Uni<Void>> streamFn) {
        if (fieldName.isEmpty()) {
            return handleError(toolCall, "field_name is required for 'remove' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }
        handler.sendProcessingChunk(chunkHandler, connectionId, "Removing user data...");
        boolean removed = listener.getUserData() != null && listener.getUserData().getData().remove(fieldName) != null;
        if (!removed) {
            JsonObject payload = new JsonObject().put("ok", true).put("action", "remove")
                    .put("field_name", fieldName).put("removed", false).put("message", "Field not found");
            handler.addToolUseToHistory(toolCall, conversationHistory);
            handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
            return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
        }
        return listenerService.updateUserData(listener.getId(), listener.getUserData())
                .flatMap(ignored -> {
                    JsonObject payload = new JsonObject().put("ok", true).put("action", "remove")
                            .put("field_name", fieldName).put("removed", true).put("message", "User data removed successfully");
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> handleError(toolCall, "Failed to remove user data: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn));
    }

    private static Uni<Void> handleAddLabel(LlmToolCall toolCall, Listener listener, String labelIdentifier,
                                            ListenerService listenerService, ListenerLabelCache labelCache,
                                            ListenerDataToolHandler handler, Consumer<String> chunkHandler, String connectionId,
                                            List<LlmMessage> conversationHistory, String systemPromptCall2, Function<LlmRequest, Uni<Void>> streamFn) {
        if (labelIdentifier.isEmpty()) {
            return handleError(toolCall, "label_identifier is required for 'add_label' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }
        return labelCache.getOrLoad(labelIdentifier).flatMap(labelId -> {
            if (labelId == null) {
                return handleError(toolCall, "Unknown label: " + labelIdentifier, handler, conversationHistory, systemPromptCall2, streamFn);
            }
            List<UUID> labels = listener.getLabels();
            if (labels.contains(labelId)) {
                JsonObject payload = new JsonObject().put("ok", true).put("action", "add_label")
                        .put("label_identifier", labelIdentifier).put("already_had_label", true).put("message", "Listener already has this label");
                handler.addToolUseToHistory(toolCall, conversationHistory);
                handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
            }
            handler.sendProcessingChunk(chunkHandler, connectionId, "Adding label...");
            labels.add(labelId);
            return listenerService.updateLabels(listener.getId(), labels)
                    .flatMap(ignored -> {
                        JsonObject payload = new JsonObject().put("ok", true).put("action", "add_label")
                                .put("label_identifier", labelIdentifier).put("already_had_label", false).put("message", "Label added successfully");
                        handler.addToolUseToHistory(toolCall, conversationHistory);
                        handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                    })
                    .onFailure().recoverWithUni(err -> handleError(toolCall, "Failed to add label: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn));
        });
    }

    private static Uni<Void> handleRemoveLabel(LlmToolCall toolCall, Listener listener, String labelIdentifier,
                                               ListenerService listenerService, ListenerLabelCache labelCache,
                                               ListenerDataToolHandler handler, Consumer<String> chunkHandler, String connectionId,
                                               List<LlmMessage> conversationHistory, String systemPromptCall2, Function<LlmRequest, Uni<Void>> streamFn) {
        if (labelIdentifier.isEmpty()) {
            return handleError(toolCall, "label_identifier is required for 'remove_label' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }
        UUID labelId = labelCache.get(labelIdentifier);
        if (labelId == null) {
            return handleError(toolCall, "Unknown label: " + labelIdentifier, handler, conversationHistory, systemPromptCall2, streamFn);
        }
        handler.sendProcessingChunk(chunkHandler, connectionId, "Removing label...");
        boolean removed = listener.getLabels().remove(labelId);
        if (!removed) {
            JsonObject payload = new JsonObject().put("ok", true).put("action", "remove_label")
                    .put("label_identifier", labelIdentifier).put("removed", false).put("message", "Label was not assigned");
            handler.addToolUseToHistory(toolCall, conversationHistory);
            handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
            return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
        }
        return listenerService.updateLabels(listener.getId(), listener.getLabels())
                .flatMap(ignored -> {
                    JsonObject payload = new JsonObject().put("ok", true).put("action", "remove_label")
                            .put("label_identifier", labelIdentifier).put("removed", true).put("message", "Label removed successfully");
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> handleError(toolCall, "Failed to remove label: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn));
    }

    private static Uni<Void> handleError(LlmToolCall toolCall, String errorMessage, ListenerDataToolHandler handler,
                                         List<LlmMessage> conversationHistory, String systemPromptCall2,
                                         Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject errorPayload = new JsonObject().put("ok", false).put("error", errorMessage);
        handler.addToolUseToHistory(toolCall, conversationHistory);
        handler.addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap,
            ListenerService listenerService,
            ListenerLabelCache labelCache,
            long userId) {
        String action = (String) inputMap.getOrDefault("action", "get");
        String fieldName = (String) inputMap.getOrDefault("field_name", "");
        String fieldValue = (String) inputMap.getOrDefault("field_value", "");
        String labelIdentifier = (String) inputMap.getOrDefault("label_identifier", "");

        // Owner and developer labels decide what an agent may reveal, so they stay out of reach of
        // chat: only datanest/admin assigns them, whatever the model was talked into asking for.
        if (("add_label".equals(action) || "remove_label".equals(action)) && !ASSIGNABLE_LABELS.contains(labelIdentifier)) {
            LOGGER.warnf("[ListenerData/execute] refused %s of privileged label '%s' userId=%d", action, labelIdentifier, userId);
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false)
                            .put("error", "Label '" + labelIdentifier + "' cannot be changed from chat").encode()));
        }

        return listenerService.getByUserId(userId)
                .chain(listener -> {
                    if (listener == null) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Listener not found").encode()));
                    }
                    return switch (action) {
                        case "get" -> {
                            List<String> resolvedLabels = labelCache.resolveToIdentifiers(listener.getLabels());
                            JsonObject payload = new JsonObject()
                                    .put("ok", true)
                                    .put("listener_id", listener.getId().toString())
                                    .put("user_id", listener.getUserId())
                                    .put("localized_name", JsonObject.mapFrom(listener.getLocalizedName()))
                                    .put("nick_name", JsonObject.mapFrom(listener.getNickName()))
                                    .put("user_data", listener.getUserData() != null ? JsonObject.mapFrom(listener.getUserData().getData()) : new JsonObject())
                                    .put("labels", resolvedLabels);
                            yield Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(payload.encode()));
                        }
                        case "set" -> {
                            if (fieldName.isEmpty() || fieldValue.isEmpty()) {
                                yield Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                        new JsonObject().put("ok", false).put("error", "field_name and field_value required").encode()));
                            }
                            String sanitized = fieldValue.replaceAll("[\\r\\n\\t]", " ").replaceAll("\\{\\{.*?}}", "").trim();
                            if (listener.getUserData() == null) listener.setUserData(new com.semantyca.core.model.UserData(new HashMap<>()));
                            listener.getUserData().put(fieldName, sanitized);
                            yield listenerService.updateUserData(listener.getId(), listener.getUserData())
                                    .map(v -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                            new JsonObject().put("ok", true).put("action", "set").put("field_name", fieldName).encode()))
                                    .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
                        }
                        case "remove" -> {
                            boolean removed = listener.getUserData() != null && listener.getUserData().getData().remove(fieldName) != null;
                            if (!removed) {
                                yield Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                        new JsonObject().put("ok", true).put("removed", false).encode()));
                            }
                            yield listenerService.updateUserData(listener.getId(), listener.getUserData())
                                    .map(v -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                            new JsonObject().put("ok", true).put("removed", true).encode()))
                                    .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
                        }
                        case "add_label" -> labelCache.getOrLoad(labelIdentifier)
                                .chain(labelId -> {
                                    if (labelId == null) {
                                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                                new JsonObject().put("ok", false).put("error", "Unknown label: " + labelIdentifier).encode()));
                                    }
                                    if (listener.getLabels().contains(labelId)) {
                                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                                new JsonObject().put("ok", true).put("already_had_label", true).encode()));
                                    }
                                    listener.getLabels().add(labelId);
                                    return listenerService.updateLabels(listener.getId(), listener.getLabels())
                                            .map(v -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                                    new JsonObject().put("ok", true).put("already_had_label", false).encode()))
                                            .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                                    new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
                                });
                        case "remove_label" -> {
                            UUID labelId = labelCache.get(labelIdentifier);
                            if (labelId == null) {
                                yield Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                        new JsonObject().put("ok", false).put("error", "Unknown label").encode()));
                            }
                            boolean removed = listener.getLabels().remove(labelId);
                            yield listenerService.updateLabels(listener.getId(), listener.getLabels())
                                    .map(v -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                            new JsonObject().put("ok", true).put("removed", removed).encode()))
                                    .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
                        }
                        default -> Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Unknown action: " + action).encode()));
                    };
                })
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
