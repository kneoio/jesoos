package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.mixpla.model.filter.ListenerFilter;
import com.semantyca.officeframe.model.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class AudienceToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudienceToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            String stationSlug,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        AudienceToolHandler handler = new AudienceToolHandler();
        return handleListListeners(toolUse, inputMap, listenerService, stationSlug, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn, handler);
    }

    private static Uni<Void> handleListListeners(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            String stationSlug,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn,
            AudienceToolHandler handler
    ) {
        String searchTerm = inputMap.getOrDefault("search_term", JsonValue.from("")).toString().replace("\"", "");
        handler.sendProcessingChunk(chunkHandler, connectionId, "Searching listeners...");

        String finalSearchTerm = searchTerm.isEmpty() ? null : searchTerm;

        ListenerFilter filter = new ListenerFilter();
        if (finalSearchTerm != null) {
            filter.setSearchTerm(finalSearchTerm);
        }

        if (inputMap.containsKey("countries")) {
            var countriesOpt = inputMap.get("countries").asArray();
            if (countriesOpt.isPresent()) {
                List<JsonValue> countriesArray = (List<JsonValue>) countriesOpt.get();
                if (!countriesArray.isEmpty()) {
                    List<CountryCode> countryCodes = new ArrayList<>();
                    for (JsonValue countryValue : countriesArray) {
                        try {
                            String countryStr = countryValue.toString().replace("\"", "");
                            countryCodes.add(CountryCode.valueOf(countryStr));
                        } catch (Exception ignored) {}
                    }
                    if (!countryCodes.isEmpty()) {
                        filter.setCountries(countryCodes);
                    }
                }
            }
        }

        ListenerFilter finalFilter = filter.isActivated() ? filter : null;

        return listenerService.getBrandListeners(stationSlug, 100, 0, SuperUser.build(), finalFilter)
                .flatMap(brandListeners -> {
                    int count = brandListeners.size();
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Found " + count + " listener" + (count != 1 ? "s" : ""));

                    JsonArray listenersJson = new JsonArray();
                    brandListeners.forEach(bl -> {
                        ListenerDTO listener = bl.getListenerDTO();
                        JsonObject listenerObj = new JsonObject()
                                .put("id", listener.getId().toString())
                                .put("slugName", listener.getSlugName());

                        if (listener.getNickName() != null && !listener.getNickName().isEmpty()) {
                            Set<String> nicknames = listener.getNickName().get(LanguageCode.en);
                            if (nicknames != null && !nicknames.isEmpty()) {
                                listenerObj.put("nickname", String.join(", ", nicknames));
                            }
                        }
                        if (listener.getLocalizedName() != null && !listener.getLocalizedName().isEmpty()) {
                            listenerObj.put("name", listener.getLocalizedName().get(LanguageCode.en));
                        }
                        if (listener.getUserData() != null && !listener.getUserData().isEmpty()) {
                            JsonObject userDataJson = new JsonObject();
                            listener.getUserData().forEach(userDataJson::put);
                            listenerObj.put("userData", userDataJson);
                        }
                        
                        listenersJson.add(listenerObj);
                    });

                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("count", count)
                            .put("listeners", listenersJson);

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    LOGGER.debug("[ListListeners] Calling follow-up LLM stream");
                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListListeners] Failed to list listeners", err);
                    JsonObject errorPayload = new JsonObject()
                            .put("ok", false)
                            .put("error", "Failed to list listeners: " + err.getMessage());

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                });
    }
}
