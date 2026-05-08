package com.semantyca.jesoos.service.chat.llm;

import com.semantyca.jesoos.config.JesoosConfig;

public final class OpenAiLlmProviderAdapter implements LlmProviderAdapter {
    @Override
    public String providerId() {
        return "openai";
    }

    @Override
    public ChatLlmClient createClient(JesoosConfig config) {
        return new OpenAiChatLlmClient(config.getOpenAiApiKey());
    }

    @Override
    public String modelFor(LlmUseCase useCase) {
        return switch (useCase) {
            case MAIN_CHAT -> LlmModels.GPT_4_1;
            case FOLLOW_UP, INTENT_CLASSIFIER -> LlmModels.GPT_4_1_MINI;
        };
    }
}
