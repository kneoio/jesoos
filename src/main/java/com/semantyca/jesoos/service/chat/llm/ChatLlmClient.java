package com.semantyca.jesoos.service.chat.llm;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface ChatLlmClient {

    CompletionStage<LlmResponse> createMessage(LlmRequest request);

    CompletionStage<String> streamText(LlmRequest request, Consumer<String> chunkConsumer);
}
