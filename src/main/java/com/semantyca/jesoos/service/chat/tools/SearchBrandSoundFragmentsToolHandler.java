package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class SearchBrandSoundFragmentsToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(SearchBrandSoundFragmentsToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            AiHelperService aiHelperService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        SearchBrandSoundFragmentsToolHandler handler = new SearchBrandSoundFragmentsToolHandler();
        String brandName = inputMap.getOrDefault("brandName", JsonValue.from("")).toString().replace("\"", "");
        String keyword = inputMap.containsKey("keyword") ? inputMap.get("keyword").toString().replace("\"", "") : "";

        if (brandName.isEmpty()) {
            LOGGER.error("[SearchSoundFragments] brandName is required but was not provided");
            return Uni.createFrom().failure(new IllegalArgumentException("brandName is required"));
        }

        List<String> genres = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (inputMap.containsKey("genres")) {
            try {
                JsonValue genresValue = inputMap.get("genres");
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(genresValue.toString());
                if (node.isArray()) {
                    node.forEach(n -> genres.add(n.asText()));
                }
            } catch (Exception ignored) {}
        }
        if (inputMap.containsKey("labels")) {
            try {
                JsonValue labelsValue = inputMap.get("labels");
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(labelsValue.toString());
                if (node.isArray()) {
                    node.forEach(n -> labels.add(n.asText()));
                }
            } catch (Exception ignored) {}
        }

        Integer limit = null;
        Integer offset = null;
        try {
            if (inputMap.containsKey("limit")) {
                limit = Integer.parseInt(inputMap.get("limit").toString());
            }
        } catch (Exception ignored) {}
        try {
            if (inputMap.containsKey("offset")) {
                offset = Integer.parseInt(inputMap.get("offset").toString());
            }
        } catch (Exception ignored) {}

        LOGGER.infof("[SearchSoundFragments] AI requested search - brandName: '%s', keyword: '%s', genres: %s, labels: %s, limit: %s, offset: %s",
                brandName, keyword, genres, labels, limit, offset);

        String progressMsg = keyword.isBlank() ? "Browsing song library..." : "Searching for songs: " + keyword + "...";
        handler.sendProcessingChunk(chunkHandler, connectionId, progressMsg);

        return aiHelperService.searchBrandSoundFragmentsForAi(brandName, keyword, genres, labels, limit, offset)
                .flatMap(list -> {
                    LOGGER.infof("[SearchSoundFragments] Search completed - found %s songs for keyword: '%s'", list.size(), keyword);
                    if (!list.isEmpty()) {
                        LOGGER.info("[SearchSoundFragments] First 3 results:");
                        list.stream().limit(3).forEach(f -> 
                            LOGGER.infof("[SearchSoundFragments]   - %s by %s (ID: %s)", f.getTitle(), f.getArtist(), f.getId())
                        );
                    }
                    
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Found " + list.size() + " songs");
                    
                    JsonArray items = new JsonArray();
                    list.forEach(f -> {
                        JsonObject obj = new JsonObject()
                                .put("id", String.valueOf(f.getId()))
                                .put("title", f.getTitle())
                                .put("artist", f.getArtist())
                                .put("genres", f.getGenres())
                                .put("album", f.getAlbum())
                                .put("description", f.getDescription());
                        items.add(obj);
                    });

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, items.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                }).onFailure().recoverWithUni(err -> {
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I could not handle your request due to a technical issue.");
                    return Uni.createFrom().voidItem();
                });
    }
}
