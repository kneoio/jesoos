package com.semantyca.jesoos.service.chat.llm;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface LlmClient {
    CompletionStage<Message> createMessage(MessageCreateParams params);

    CompletionStage<String> streamText(MessageCreateParams params, Consumer<String> chunkConsumer);
}
