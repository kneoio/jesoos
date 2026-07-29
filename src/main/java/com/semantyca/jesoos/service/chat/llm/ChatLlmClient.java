package com.semantyca.jesoos.service.chat.llm;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface ChatLlmClient {

    CompletionStage<LlmResponse> createMessage(LlmRequest request);

    /**
     * Provider-native streaming (Anthropic SSE). Invokes {@code chunkConsumer} for each visible
     * text delta. Returns the full {@link LlmResponse} (text and/or tool call) when the stream ends.
     * Text deltas are not emitted when the model chooses a tool call.
     */
    CompletionStage<LlmResponse> streamMessage(LlmRequest request, Consumer<String> chunkConsumer);

    CompletionStage<String> streamText(LlmRequest request, Consumer<String> chunkConsumer);
}
