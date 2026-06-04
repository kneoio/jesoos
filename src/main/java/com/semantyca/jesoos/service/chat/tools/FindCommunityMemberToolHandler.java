package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class FindCommunityMemberToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FindCommunityMemberToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            ListenerService listenerService,
            String brandName,
            long userId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        FindCommunityMemberToolHandler handler = new FindCommunityMemberToolHandler();
        String fieldName = (String) inputMap.getOrDefault("field_name", "");
        String fieldValue = (String) inputMap.getOrDefault("field_value", "");

        LOGGER.info("[FindCommunityMember] field={}, value={}, brand={}, userId={}", fieldName, fieldValue, brandName, userId);

        if (fieldName.isEmpty() || fieldValue.isEmpty()) {
            return handleError(toolCall, "field_name and field_value are required", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        return listenerService.getByUserId(userId)
                .flatMap(currentListener -> {
                    if (currentListener == null) {
                        return handleError(toolCall, "Current listener not found.", handler, conversationHistory, systemPromptCall2, streamFn);
                    }

                    Uni<java.util.List<com.semantyca.mixpla.model.Listener>> searchUni;
                    if ("interests".equals(fieldName)) {
                        String city = currentListener.getUserData() != null
                                ? (String) currentListener.getUserData().getData().getOrDefault("city", null)
                                : null;
                        searchUni = listenerService.findCommunityMembersByInterest(brandName, currentListener.getId(), fieldValue, city);
                    } else {
                        searchUni = listenerService.findCommunityMembers(brandName, currentListener.getId(), fieldName, fieldValue);
                    }

                    return searchUni.flatMap(members -> {
                        JsonArray matches = new JsonArray();
                        for (var member : members) {
                            JsonObject entry = new JsonObject();
                            if (member.getUserData() != null) {
                                Object name = member.getUserData().getData().get("preferred_name");
                                if (name != null) entry.put("preferred_name", name.toString());
                                Object company = member.getUserData().getData().get("company");
                                if (company != null) entry.put("company", company.toString());
                                Object group = member.getUserData().getData().get("community_group");
                                if (group != null) entry.put("community_group", group.toString());
                                Object city = member.getUserData().getData().get("city");
                                if (city != null) entry.put("city", city.toString());
                            }
                            if (!entry.isEmpty()) matches.add(entry);
                        }

                        JsonObject payload = new JsonObject()
                                .put("ok", true).put("field_name", fieldName)
                                .put("field_value", fieldValue).put("matches", matches);

                        handler.addToolUseToHistory(toolCall, conversationHistory);
                        handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                    });
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[FindCommunityMember] Failed field={}, value={}", fieldName, fieldValue, err);
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I couldn't look that up right now, please try again.");
                    return Uni.createFrom().voidItem();
                });
    }

    private static Uni<Void> handleError(LlmToolCall toolCall, String errorMessage,
                                         FindCommunityMemberToolHandler handler,
                                         List<LlmMessage> conversationHistory, String systemPromptCall2,
                                         Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject errorPayload = new JsonObject().put("ok", false).put("error", errorMessage);
        handler.addToolUseToHistory(toolCall, conversationHistory);
        handler.addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap, ListenerService listenerService, String brandName, long userId) {
        String fieldName = (String) inputMap.getOrDefault("field_name", "");
        String fieldValue = (String) inputMap.getOrDefault("field_value", "");
        if (fieldName.isEmpty() || fieldValue.isEmpty()) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "field_name and field_value are required").encode()));
        }
        return listenerService.getByUserId(userId)
                .chain(currentListener -> {
                    if (currentListener == null) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Listener not found").encode()));
                    }
                    Uni<java.util.List<com.semantyca.mixpla.model.Listener>> searchUni =
                            "interests".equals(fieldName)
                                    ? listenerService.findCommunityMembersByInterest(brandName, currentListener.getId(), fieldValue,
                                            currentListener.getUserData() != null ? (String) currentListener.getUserData().getData().get("city") : null)
                                    : listenerService.findCommunityMembers(brandName, currentListener.getId(), fieldName, fieldValue);
                    return searchUni.map(members -> {
                        JsonArray matches = new JsonArray();
                        for (var member : members) {
                            JsonObject entry = new JsonObject();
                            if (member.getUserData() != null) {
                                member.getUserData().getData().forEach((k, v) -> { if (v != null) entry.put(k.toString(), v.toString()); });
                            }
                            if (!entry.isEmpty()) matches.add(entry);
                        }
                        return com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", true).put("matches", matches).encode());
                    });
                })
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
