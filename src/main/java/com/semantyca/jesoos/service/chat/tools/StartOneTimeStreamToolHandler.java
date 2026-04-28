package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.service.OneTimeStreamService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class StartOneTimeStreamToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartOneTimeStreamToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            OneTimeStreamService oneTimeStreamService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        StartOneTimeStreamToolHandler handler = new StartOneTimeStreamToolHandler();

        String brandSlugName = inputMap.getOrDefault("brandSlugName", JsonValue.from("")).toString().replace("\"", "");
        String scriptIdStr = inputMap.getOrDefault("scriptId", JsonValue.from("")).toString().replace("\"", "");
        boolean startImmediately = true;
        if (inputMap.containsKey("startImmediately")) {
            startImmediately = Boolean.parseBoolean(inputMap.get("startImmediately").toString());
        }

        if (brandSlugName.isEmpty() || scriptIdStr.isEmpty()) {
            return handler.buildErrorResponse(toolUse, "Missing required parameters: brandSlugName, scriptId",
                    conversationHistory, systemPromptCall2, streamFn);
        }

        UUID scriptId;
        try {
            scriptId = UUID.fromString(scriptIdStr);
        } catch (Exception e) {
            return handler.buildErrorResponse(toolUse, "Invalid scriptId format", conversationHistory, systemPromptCall2, streamFn);
        }

        LOGGER.info("[StartOneTimeStream] brand={}, scriptId={}, startImmediately={}", brandSlugName, scriptId, startImmediately);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Starting one-time stream...");

        boolean finalStartImmediately = startImmediately;
        return oneTimeStreamService.run(brandSlugName, scriptId, Map.of(), finalStartImmediately, SuperUser.build())
                .flatMap(stream -> {
                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("slugName", stream.getSlugName())
                            .put("id", stream.getId().toString())
                            .put("status", stream.getStatus().name());

                    handler.sendProcessingChunk(chunkHandler, connectionId, "Stream started: " + stream.getSlugName());
                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[StartOneTimeStream] Failed", err);
                    return handler.buildErrorResponse(toolUse, err.getMessage(), conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private Uni<Void> buildErrorResponse(
            ToolUseBlock toolUse, String message,
            List<MessageParam> conversationHistory, String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        JsonObject payload = new JsonObject().put("ok", false).put("error", message);
        addToolUseToHistory(toolUse, conversationHistory);
        addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
        return streamFn.apply(buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
