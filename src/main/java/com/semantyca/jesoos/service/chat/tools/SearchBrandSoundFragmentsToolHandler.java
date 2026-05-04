package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
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
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            AiHelperService aiHelperService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        SearchBrandSoundFragmentsToolHandler handler = new SearchBrandSoundFragmentsToolHandler();
        String brandName = (String) inputMap.getOrDefault("brandName", "");
        String keyword = inputMap.containsKey("keyword") ? (String) inputMap.get("keyword") : "";

        if (brandName.isEmpty()) {
            LOGGER.error("[SearchSoundFragments] brandName is required but was not provided");
            return Uni.createFrom().failure(new IllegalArgumentException("brandName is required"));
        }

        List<String> genres = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (inputMap.containsKey("genres") && inputMap.get("genres") instanceof List<?> genreList) {
            genreList.forEach(g -> genres.add(g.toString()));
        }
        if (inputMap.containsKey("labels") && inputMap.get("labels") instanceof List<?> labelList) {
            labelList.forEach(l -> labels.add(l.toString()));
        }

        Integer limit = null;
        Integer offset = null;
        try {
            if (inputMap.containsKey("limit")) limit = ((Number) inputMap.get("limit")).intValue();
        } catch (Exception ignored) {}
        try {
            if (inputMap.containsKey("offset")) offset = ((Number) inputMap.get("offset")).intValue();
        } catch (Exception ignored) {}

        LOGGER.infof("[SearchSoundFragments] AI requested search - brandName: '%s', keyword: '%s', genres: %s, labels: %s, limit: %s, offset: %s",
                brandName, keyword, genres, labels, limit, offset);

        String progressMsg = keyword.isBlank() ? "Browsing song library..." : "Searching for songs: " + keyword + "...";
        handler.sendProcessingChunk(chunkHandler, connectionId, progressMsg);

        return aiHelperService.searchBrandSoundFragmentsForAi(brandName, keyword, genres, labels, limit, offset)
                .flatMap(list -> {
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Found " + list.size() + " songs");

                    JsonArray items = new JsonArray();
                    list.forEach(f -> items.add(new JsonObject()
                            .put("id", String.valueOf(f.getId()))
                            .put("title", f.getTitle())
                            .put("artist", f.getArtist())
                            .put("genres", f.getGenres())
                            .put("album", f.getAlbum())
                            .put("description", f.getDescription())));

                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, items.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                }).onFailure().recoverWithUni(err -> {
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I could not handle your request due to a technical issue.");
                    return Uni.createFrom().voidItem();
                });
    }
}
