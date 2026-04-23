package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class SendUICommandToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SendUICommandToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        SendUICommandToolHandler handler = new SendUICommandToolHandler();

        String command = inputMap.getOrDefault("command", JsonValue.from(""))
                .toString().replace("\"", "").trim();

        JsonObject payload = new JsonObject();
        try {
            if (inputMap.containsKey("payload")) {
                String raw = inputMap.get("payload").toString();
                if (!raw.isBlank() && !raw.equals("null")) {
                    payload = new JsonObject(raw);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[SendUICommand] Invalid payload, using empty object: {}", e.getMessage());
        }

        if (command.isBlank()) {
            JsonObject err = new JsonObject().put("ok", false).put("error", "command is required");
            handler.addToolUseToHistory(toolUse, conversationHistory);
            handler.addToolResultToHistory(toolUse, err.encode(), conversationHistory);
            return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
        }

        LOGGER.info("[SendUICommand] Sending command '{}' to connection {}", command, connectionId);
        chunkHandler.accept(ChatMessageDTO.command(command, payload, connectionId).build().toJson());

        JsonObject result = new JsonObject()
                .put("ok", true)
                .put("command", command);

        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, result.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
