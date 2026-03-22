package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.SimpleReferenceEntity;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.officeframe.dto.LabelDTO;
import com.semantyca.officeframe.model.Label;
import com.semantyca.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ListenerDataToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerDataToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            LabelService labelService,
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

                    return listenerService.getDTO(listener.getId(), SuperUser.build(), null)
                            .flatMap(listenerDTO -> {
                                return switch (action) {
                                    case "get" ->
                                            handleGet(toolUse, listenerDTO, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn, labelService);
                                    case "set" ->
                                            handleSet(toolUse, listenerDTO, fieldName, fieldValue, listenerService, labelService, stationSlug, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                                    case "remove" ->
                                            handleRemove(toolUse, listenerDTO, fieldName, listenerService, stationSlug, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                                    default ->
                                            handleError(toolUse, "Invalid action: " + action, handler, conversationHistory, systemPromptCall2, streamFn);
                                };
                            });
                });
    }

    private static Uni<Void> handleGet(
            ToolUseBlock toolUse,
            ListenerDTO listenerDTO,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn,
            LabelService labelService
    ) {
        handler.sendProcessingChunk(chunkHandler, connectionId, "Retrieving listener data...");

        Uni<List<String>> labelsUni = Uni.createFrom().item(List.of());
        Uni<List<String>> availableLabelsUni = labelService.getOfCategory("listener", LanguageCode.en)
                .onFailure().recoverWithItem(List.of())
                .map(labels -> labels.stream()
                        .map(LabelDTO::getIdentifier)
                        .filter(name -> !name.isEmpty())
                        .collect(Collectors.toList()));
        
        if (listenerDTO.getLabels() != null && !listenerDTO.getLabels().isEmpty()) {
            List<Uni<Label>> labelUnis = listenerDTO.getLabels().stream()
                    .map(labelId -> labelService.getById(labelId)
                            .onFailure().recoverWithNull())
                    .collect(Collectors.toList());
            
            labelsUni = Uni.join().all(labelUnis).andFailFast().map(list -> {
                return list.stream()
                        .filter(Objects::nonNull)
                        .map(SimpleReferenceEntity::getIdentifier)
                        .filter(name -> !name.isEmpty())
                        .collect(Collectors.toList());
            });
        }

        return labelsUni.chain(resolvedLabels -> 
            availableLabelsUni.chain(allLabels -> {
                JsonObject payload = new JsonObject()
                        .put("ok", true)
                        .put("listener_id", listenerDTO.getId().toString())
                        .put("user_id", listenerDTO.getUserId())
                        .put("email", listenerDTO.getEmail())
                        .put("slug_name", listenerDTO.getSlugName())
                        .put("localized_name", JsonObject.mapFrom(listenerDTO.getLocalizedName()))
                        .put("nick_name", JsonObject.mapFrom(listenerDTO.getNickName()))
                        .put("user_data", listenerDTO.getUserData() != null ? JsonObject.mapFrom(listenerDTO.getUserData()) : new JsonObject())
                        .put("labels", resolvedLabels)
                        .put("available_labels", allLabels);

                handler.addToolUseToHistory(toolUse, conversationHistory);
                handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                return streamFn.apply(secondCallParams);
            })
        );
    }

    private static Uni<Void> handleSet(
            ToolUseBlock toolUse,
            ListenerDTO listenerDTO,
            String fieldName,
            String fieldValue,
            ListenerService listenerService,
            LabelService labelService,
            String stationSlug,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        if (fieldName.isEmpty()) {
            return handleError(toolUse, "field_name is required for 'set' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }
        if (fieldValue.isEmpty() && !"labels".equals(fieldName)) {
            return handleError(toolUse, "field_value is required for 'set' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Storing user data...");

        if ("labels".equals(fieldName)) {
            String[] labelNames = fieldValue.split(",");
            List<Uni<Label>> labelUnis = Stream.of(labelNames)
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .map(name -> labelService.findByIdentifier(name)
                            .onFailure().recoverWithNull())
                    .collect(Collectors.toList());

            return Uni.join().all(labelUnis).andFailFast().chain(list -> {
                        List<UUID> labelIds = list.stream()
                                .filter(Objects::nonNull)
                                .map(Label::getId)
                                .collect(Collectors.toList());

                        listenerDTO.setLabels(labelIds);
                        return listenerService.upsert(listenerDTO.getId().toString(), listenerDTO, stationSlug, SuperUser.build());
                    })
                    .flatMap(updatedListener -> {
                        LOGGER.info("[ListenerData] Set labels '{}' for listener {}", fieldValue, listenerDTO.getId());

                        JsonObject payload = new JsonObject()
                                .put("ok", true)
                                .put("action", "set")
                                .put("field_name", fieldName)
                                .put("field_value", fieldValue)
                                .put("message", "Labels updated successfully");

                        handler.addToolUseToHistory(toolUse, conversationHistory);
                        handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                        MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                        return streamFn.apply(secondCallParams);
                    })
                    .onFailure().recoverWithUni(err -> {
                        LOGGER.error("[ListenerData] Failed to set labels", err);
                        return handleError(toolUse, "Failed to set labels: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                    });
        }

        if (listenerDTO.getUserData() == null) {
            listenerDTO.setUserData(new HashMap<>());
        }
        listenerDTO.getUserData().put(fieldName, fieldValue);

        return listenerService.upsert(listenerDTO.getId().toString(), listenerDTO, stationSlug, SuperUser.build())
                .flatMap(updatedListener -> {
                    LOGGER.info("[ListenerData] Set field '{}' = '{}' for listener {}", fieldName, fieldValue, listenerDTO.getId());

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
            ListenerDTO listenerDTO,
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
        if (listenerDTO.getUserData() != null) {
            removed = listenerDTO.getUserData().remove(fieldName) != null;
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

        return listenerService.upsert(listenerDTO.getId().toString(), listenerDTO, stationSlug, SuperUser.build())
                .flatMap(updatedListener -> {
                    LOGGER.info("[ListenerData] Removed field '{}' for listener {}", fieldName, listenerDTO.getId());

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
