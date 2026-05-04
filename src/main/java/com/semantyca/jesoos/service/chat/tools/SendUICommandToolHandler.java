package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class SendUICommandToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(SendUICommandToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        SendUICommandToolHandler handler = new SendUICommandToolHandler();
        String command = ((String) inputMap.getOrDefault("command", "")).trim();

        JsonObject payload = new JsonObject();
        try {
            Object rawPayload = inputMap.get("payload");
            if (rawPayload instanceof Map<?, ?> payloadMap) {
                payloadMap.forEach((k, v) -> payload.put(k.toString(), v));
            }
        } catch (Exception e) {
            LOGGER.warnf("[SendUICommand] Invalid payload, using empty object: %s", e.getMessage());
        }

        if (command.isBlank()) {
            JsonObject err = new JsonObject().put("ok", false).put("error", "command is required");
            handler.addToolUseToHistory(toolCall, conversationHistory);
            handler.addToolResultToHistory(toolCall, err.encode(), conversationHistory);
            return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
        }

        LOGGER.infof("[SendUICommand] Sending command '%s' to connection %s", command, connectionId);
        chunkHandler.accept(ChatMessageDTO.command(command, payload, connectionId).build().toJson());

        JsonObject result = new JsonObject().put("ok", true).put("command", command);
        handler.addToolUseToHistory(toolCall, conversationHistory);
        handler.addToolResultToHistory(toolCall, result.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
