package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class SearchBrandSoundFragmentsToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchBrandSoundFragmentsToolHandler.class);

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
        String keyword = inputMap.getOrDefault("keyword", JsonValue.from("")).toString().replace("\"", "");
        
        if (brandName.isEmpty()) {
            LOGGER.error("[SearchSoundFragments] brandName is required but was not provided");
            return Uni.createFrom().failure(new IllegalArgumentException("brandName is required"));
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

        LOGGER.info("[SearchSoundFragments] AI requested search - brandName: '{}', keyword: '{}', limit: {}, offset: {}, connectionId: {}",
                brandName, keyword, limit, offset, connectionId);

        handler.sendProcessingChunk(chunkHandler, connectionId, String.format("Searching for songs: %s...", keyword));

        return aiHelperService.searchBrandSoundFragmentsForAi(brandName, keyword, limit, offset)
                .flatMap(list -> {
                    LOGGER.info("[SearchSoundFragments] Search completed - found {} songs for keyword: '{}'", list.size(), keyword);
                    if (!list.isEmpty()) {
                        LOGGER.info("[SearchSoundFragments] First 3 results:");
                        list.stream().limit(3).forEach(f -> 
                            LOGGER.info("[SearchSoundFragments]   - {} by {} (ID: {})", f.getTitle(), f.getArtist(), f.getId())
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
                                .put("labels", f.getLabels())
                                .put("album", f.getAlbum())
                                .put("description", f.getDescription())
                                .put("playedByBrandCount", f.getPlayedByBrandCount())
                                .put("lastTimePlayedByBrand", String.valueOf(f.getLastTimePlayedByBrand()));
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
