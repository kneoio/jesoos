package com.semantyca.jesoos.external;

import com.semantyca.core.llm.AnthropicTextClient;
import com.semantyca.core.llm.GroqTextClient;
import com.semantyca.core.llm.LlmTextClient;
import com.semantyca.jesoos.config.JesoosConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class LlmTextClientProducer {

    @Inject
    JesoosConfig config;

    @Inject
    AnthropicTextClient anthropicTextClient;

    @Inject
    GroqTextClient groqTextClient;

    @Produces
    @ApplicationScoped
    public LlmTextClient produce() {
        String provider = config.getLlmProvider();
        return switch (provider) {
            case "groq" -> groqTextClient;
            default -> anthropicTextClient;
        };
    }
}
