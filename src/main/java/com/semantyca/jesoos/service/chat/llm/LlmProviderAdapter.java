package com.semantyca.jesoos.service.chat.llm;

import com.semantyca.jesoos.config.JesoosConfig;

public interface LlmProviderAdapter {
    String providerId();
    ChatLlmClient createClient(JesoosConfig config);
    String modelFor(LlmUseCase useCase);
}
