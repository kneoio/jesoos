package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.service.ListenerService;
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
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            String brandName,
            long userId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        FindCommunityMemberToolHandler handler = new FindCommunityMemberToolHandler();
        String fieldName = inputMap.getOrDefault("field_name", JsonValue.from("")).toString().replace("\"", "");
        String fieldValue = inputMap.getOrDefault("field_value", JsonValue.from("")).toString().replace("\"", "");

        LOGGER.info("[FindCommunityMember] field={}, value={}, brand={}, userId={}", fieldName, fieldValue, brandName, userId);

        if (fieldName.isEmpty() || fieldValue.isEmpty()) {
            return handleError(toolUse, "field_name and field_value are required", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        return listenerService.getByUserId(userId)
                .flatMap(currentListener -> {
                    if (currentListener == null) {
                        return handleError(toolUse, "Current listener not found.", handler, conversationHistory, systemPromptCall2, streamFn);
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

                    return searchUni
                            .flatMap(members -> {
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
                                    if (!entry.isEmpty()) {
                                        matches.add(entry);
                                    }
                                }

                                JsonObject payload = new JsonObject()
                                        .put("ok", true)
                                        .put("field_name", fieldName)
                                        .put("field_value", fieldValue)
                                        .put("matches", matches);

                                LOGGER.info("[FindCommunityMember] Found {} matches for {}={}", matches.size(), fieldName, fieldValue);

                                handler.addToolUseToHistory(toolUse, conversationHistory);
                                handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                                MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                                return streamFn.apply(secondCallParams);
                            });
                });
    }

    private static Uni<Void> handleError(
            ToolUseBlock toolUse,
            String errorMessage,
            FindCommunityMemberToolHandler handler,
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
