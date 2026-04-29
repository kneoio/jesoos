package com.semantyca.jesoos.service.chat.llm;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface LlmClient {
    // TODO(LLM-ABSTRACTION): replace Anthropic-specific input/output types with provider-neutral DTOs from llm.dto.
    CompletionStage<Message> createMessage(MessageCreateParams params);

    // TODO(LLM-ABSTRACTION): switch this method to accept LlmRequest and return a neutral stream/result contract.
    CompletionStage<String> streamText(MessageCreateParams params, Consumer<String> chunkConsumer);
}
