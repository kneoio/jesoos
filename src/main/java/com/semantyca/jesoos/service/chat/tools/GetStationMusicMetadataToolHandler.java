package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class GetStationMusicMetadataToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(GetStationMusicMetadataToolHandler.class);

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
        GetStationMusicMetadataToolHandler handler = new GetStationMusicMetadataToolHandler();

        LOGGER.infof("[GetStationMusicMetadata] Fetching genres and labels for connectionId: %s", connectionId);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Loading available genres and labels...");

        return aiHelperService.getStationMusicMetadata()
                .flatMap(metadata -> {
                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, metadata.encode(), conversationHistory);
                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.errorf(err, "[GetStationMusicMetadata] Failed to fetch metadata");
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I could not retrieve music metadata at this time.");
                    return Uni.createFrom().voidItem();
                });
    }
}
