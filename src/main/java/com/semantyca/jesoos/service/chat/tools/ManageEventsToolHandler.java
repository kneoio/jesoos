package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.dto.event.EventDTO;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.EventService;
import com.semantyca.mixpla.model.Event;
import com.semantyca.mixpla.model.cnst.EventPriority;
import com.semantyca.mixpla.model.cnst.EventType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;


import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class ManageEventsToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(ManageEventsToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            EventService eventService,
            BrandService brandService,
            String brandName,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        ManageEventsToolHandler handler = new ManageEventsToolHandler();
        String action = inputMap.getOrDefault("action", JsonValue.from("list")).toString().replace("\"", "");

        LOGGER.infof("[ManageEvents] action=%s, brand=%s", action, brandName);

        return switch (action) {
            case "list" -> handleList(toolUse, eventService, brandName, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
            case "upsert" -> handleUpsert(toolUse, inputMap, eventService, brandService, brandName, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
            default -> handleError(toolUse, "Unknown action: " + action, handler, conversationHistory, systemPromptCall2, streamFn);
        };
    }

    private static Uni<Void> handleList(
            ToolUseBlock toolUse,
            EventService eventService,
            String brandName,
            ManageEventsToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        handler.sendProcessingChunk(chunkHandler, connectionId, "Loading events...");

        return eventService.findForBrand(brandName, 50, 0, SuperUser.build())
                .flatMap(events -> {
                    JsonArray arr = new JsonArray();
                    for (Event e : events) {
                        arr.add(new JsonObject()
                                .put("id", e.getId().toString())
                                .put("description", e.getDescription())
                                .put("type", e.getType() != null ? e.getType().name() : null)
                                .put("priority", e.getPriority() != null ? e.getPriority().name() : null));
                    }

                    JsonObject payload = new JsonObject().put("ok", true).put("events", arr).put("count", arr.size());
                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ManageEvents] list failed", err);
                    return handleError(toolUse, "Failed to load events: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleUpsert(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            EventService eventService,
            BrandService brandService,
            String brandName,
            ManageEventsToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        String id = inputMap.containsKey("id") ? inputMap.get("id").toString().replace("\"", "") : null;
        String description = inputMap.getOrDefault("description", JsonValue.from("")).toString().replace("\"", "");
        String typeRaw = inputMap.getOrDefault("type", JsonValue.from(EventType.SPECIAL.name())).toString().replace("\"", "");
        String type;
        try {
            type = EventType.valueOf(typeRaw).name();
        } catch (IllegalArgumentException e) {
            type = EventType.SPECIAL.name();
        }
        String priorityRaw = inputMap.getOrDefault("priority", JsonValue.from(EventPriority.MEDIUM.name())).toString().replace("\"", "");
        String priority;
        try {
            priority = EventPriority.valueOf(priorityRaw).name();
        } catch (IllegalArgumentException e) {
            priority = EventPriority.MEDIUM.name();
        }

        if (description.isEmpty()) {
            return handleError(toolUse, "description is required for upsert", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Saving event...");

        String finalPriority = priority;
        String finalType = type;
        return brandService.getBySlugName(brandName)
                .flatMap(brand -> {
                    EventDTO dto = new EventDTO();
                    dto.setBrandId(brand.getId().toString());
                    dto.setDescription(description);
                    dto.setType(finalType);
                    dto.setPriority(finalPriority);
                    dto.setTimeZone(brand.getTimeZone() != null ? brand.getTimeZone().getId() : "UTC");

                    return eventService.upsert(id != null && !id.isEmpty() ? id : null, dto, SuperUser.build());
                })
                .flatMap(saved -> {
                    LOGGER.infof("[ManageEvents] upserted event id=%s", saved.getId());
                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("id", saved.getId().toString())
                            .put("description", saved.getDescription())
                            .put("message", id == null ? "Event created" : "Event updated");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ManageEvents] upsert failed", err);
                    return handleError(toolUse, "Failed to save event: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(
            ToolUseBlock toolUse,
            String message,
            ManageEventsToolHandler handler,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        JsonObject payload = new JsonObject().put("ok", false).put("error", message);
        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
