package com.semantyca.jesoos.external;

import io.smallrye.mutiny.Uni;

public interface LlmTextClient {
    Uni<LlmTextResult> createTextMessage(String model, long maxTokens, String systemPrompt, String userMessage);
}
