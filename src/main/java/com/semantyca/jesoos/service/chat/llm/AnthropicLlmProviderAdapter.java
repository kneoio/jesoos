package com.semantyca.jesoos.service.chat.llm;

import com.semantyca.jesoos.config.JesoosConfig;

public final class AnthropicLlmProviderAdapter implements LlmProviderAdapter {
    @Override
    public String providerId() {
        return "anthropic";
    }

    @Override
    public ChatLlmClient createClient(JesoosConfig config) {
        return new AnthropicChatLlmClient(config.getAnthropicApiKey());
    }

    @Override
    public String modelFor(LlmUseCase useCase) {
        return switch (useCase) {
            case MAIN_CHAT -> LlmModels.CLAUDE_SONNET_4_5;
            case FOLLOW_UP, INTENT_CLASSIFIER -> LlmModels.CLAUDE_HAIKU_4_5;
        };
    }
}
