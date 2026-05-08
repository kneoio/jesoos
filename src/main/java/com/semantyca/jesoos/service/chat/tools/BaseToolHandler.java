package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

public abstract class BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseToolHandler.class);

    protected void sendProcessingChunk(Consumer<String> chunkHandler, String connectionId, String message) {
        LOGGER.info("[ProcessingChunk] connectionId: {}, message: '{}'", connectionId, message);
        chunkHandler.accept(ChatMessageDTO.processing(message, connectionId).build().toJson());
    }

    protected void sendProcessingDone(Consumer<String> chunkHandler, String connectionId) {
        chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
    }

    protected void sendBotChunk(Consumer<String> chunkHandler, String connectionId, String username, String message) {
        chunkHandler.accept(ChatMessageDTO.bot(message, username, connectionId).build().toJson());
    }

    protected void addToolUseToHistory(LlmToolCall toolCall, List<LlmMessage> conversationHistory) {
        conversationHistory.add(LlmMessage.toolUse(toolCall));
    }

    protected void addToolResultToHistory(LlmToolCall toolCall, String resultContent, List<LlmMessage> conversationHistory) {
        conversationHistory.add(LlmMessage.toolResult(toolCall.id(), resultContent));
    }

    protected LlmRequest buildFollowUpParams(String systemPrompt, List<LlmMessage> conversationHistory) {
        return LlmRequest.builder()
                .maxTokens(1024L)
                .system(systemPrompt)
                .messages(conversationHistory)
                .build();
    }
}
