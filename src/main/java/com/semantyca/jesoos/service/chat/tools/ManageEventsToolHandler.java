package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.dto.event.EventDTO;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.EventService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
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
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            EventService eventService,
            BrandService brandService,
            String brandName,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        ManageEventsToolHandler handler = new ManageEventsToolHandler();
        String action = (String) inputMap.getOrDefault("action", "list");
        LOGGER.infof("[ManageEvents] action=%s, brand=%s", action, brandName);

        return switch (action) {
            case "list" -> handleList(toolCall, eventService, brandName, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
            case "upsert" -> handleUpsert(toolCall, inputMap, eventService, brandService, brandName, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
            default -> handleError(toolCall, "Unknown action: " + action, handler, conversationHistory, systemPromptCall2, streamFn);
        };
    }

    private static Uni<Void> handleList(LlmToolCall toolCall, EventService eventService, String brandName,
                                        ManageEventsToolHandler handler, Consumer<String> chunkHandler, String connectionId,
                                        List<LlmMessage> conversationHistory, String systemPromptCall2, Function<LlmRequest, Uni<Void>> streamFn) {
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
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ManageEvents] list failed", err);
                    return handleError(toolCall, "Failed to load events: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleUpsert(LlmToolCall toolCall, Map<String, Object> inputMap,
                                          EventService eventService, BrandService brandService, String brandName,
                                          ManageEventsToolHandler handler, Consumer<String> chunkHandler, String connectionId,
                                          List<LlmMessage> conversationHistory, String systemPromptCall2, Function<LlmRequest, Uni<Void>> streamFn) {
        String id = inputMap.containsKey("id") ? (String) inputMap.get("id") : null;
        String description = (String) inputMap.getOrDefault("description", "");
        String typeRaw = (String) inputMap.getOrDefault("type", EventType.SPECIAL.name());
        String priorityRaw = (String) inputMap.getOrDefault("priority", EventPriority.MEDIUM.name());

        String type;
        try { type = EventType.valueOf(typeRaw).name(); } catch (IllegalArgumentException e) { type = EventType.SPECIAL.name(); }
        String priority;
        try { priority = EventPriority.valueOf(priorityRaw).name(); } catch (IllegalArgumentException e) { priority = EventPriority.MEDIUM.name(); }

        if (description.isEmpty()) {
            return handleError(toolCall, "description is required for upsert", handler, conversationHistory, systemPromptCall2, streamFn);
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
                    JsonObject payload = new JsonObject()
                            .put("ok", true).put("id", saved.getId().toString())
                            .put("description", saved.getDescription())
                            .put("message", id == null ? "Event created" : "Event updated");
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ManageEvents] upsert failed", err);
                    return handleError(toolCall, "Failed to save event: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(LlmToolCall toolCall, String message, ManageEventsToolHandler handler,
                                         List<LlmMessage> conversationHistory, String systemPromptCall2,
                                         Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject payload = new JsonObject().put("ok", false).put("error", message);
        handler.addToolUseToHistory(toolCall, conversationHistory);
        handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap, EventService eventService, BrandService brandService, String brandName) {
        String action = (String) inputMap.getOrDefault("action", "list");
        if ("list".equals(action)) {
            return eventService.findForBrand(brandName, 50, 0, SuperUser.build())
                    .map(events -> {
                        JsonArray arr = new JsonArray();
                        for (Event e : events) {
                            arr.add(new JsonObject().put("id", e.getId().toString())
                                    .put("description", e.getDescription())
                                    .put("type", e.getType() != null ? e.getType().name() : null)
                                    .put("priority", e.getPriority() != null ? e.getPriority().name() : null));
                        }
                        return com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", true).put("events", arr).put("count", arr.size()).encode());
                    })
                    .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
        }
        String id = inputMap.containsKey("id") ? (String) inputMap.get("id") : null;
        String description = (String) inputMap.getOrDefault("description", "");
        String typeRaw = (String) inputMap.getOrDefault("type", EventType.SPECIAL.name());
        String priorityRaw = (String) inputMap.getOrDefault("priority", EventPriority.MEDIUM.name());
        String type; try { type = EventType.valueOf(typeRaw).name(); } catch (Exception e) { type = EventType.SPECIAL.name(); }
        String priority; try { priority = EventPriority.valueOf(priorityRaw).name(); } catch (Exception e) { priority = EventPriority.MEDIUM.name(); }
        if (description.isEmpty()) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "description is required").encode()));
        }
        String finalType = type; String finalPriority = priority;
        return brandService.getBySlugName(brandName)
                .chain(brand -> {
                    EventDTO dto = new EventDTO(); dto.setBrandId(brand.getId().toString());
                    dto.setDescription(description); dto.setType(finalType); dto.setPriority(finalPriority);
                    dto.setTimeZone(brand.getTimeZone() != null ? brand.getTimeZone().getId() : "UTC");
                    return eventService.upsert(id != null && !id.isEmpty() ? id : null, dto, SuperUser.build());
                })
                .map(saved -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", true).put("id", saved.getId().toString())
                                .put("description", saved.getDescription()).encode()))
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
