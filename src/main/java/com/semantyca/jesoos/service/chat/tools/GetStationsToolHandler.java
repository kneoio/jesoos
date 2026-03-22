package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.jesoos.dto.AvailableStationsAiDTO;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class GetStationsToolHandler extends BaseToolHandler {

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
        GetStationsToolHandler handler = new GetStationsToolHandler();
        String country = inputMap.getOrDefault("country", JsonValue.from("")).toString();

        handler.sendProcessingChunk(chunkHandler, connectionId, "Fetching stations...");

        return aiHelperService.getAllStations(null, country, null, null)
                .flatMap((AvailableStationsAiDTO stationsData) -> {
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Found " + stationsData.getRadioStations().size() + " stations");
                    
                    JsonArray stationsJson = new JsonArray();
                    stationsData.getRadioStations().forEach(station -> {
                        boolean isOnline = station.getStreamStatus() == StreamStatus.ON_LINE ||
                                         station.getStreamStatus() == StreamStatus.WARMING_UP ||
                                         station.getStreamStatus() == StreamStatus.QUEUE_SATURATED ||
                                         station.getStreamStatus() == StreamStatus.IDLE;
                        
                        JsonObject stationObj = new JsonObject()
                                .put("name", station.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                                .put("slugName", station.getSlugName())
                                .put("country", station.getCountry())
                                .put("status", station.getStreamStatus().toString())
                                .put("is_online", isOnline);
                        stationsJson.add(stationObj);
                    });

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, stationsJson.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams)
                            .onFailure().invoke(err -> {
                                System.err.println("StreamFn failed in GetStationsToolHandler: " + err.getMessage());
                                err.printStackTrace();
                                handler.sendBotChunk(chunkHandler, connectionId, "bot", "Failed to generate response: " + err.getMessage());
                            });
                })
                .onFailure().recoverWithUni(err -> {
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I could not handle your request due to a technical issue.");
                    return Uni.createFrom().voidItem();
                });
    }
}
