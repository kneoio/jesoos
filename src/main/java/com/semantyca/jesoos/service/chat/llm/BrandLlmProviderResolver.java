package com.semantyca.jesoos.service.chat.llm;

import com.semantyca.jesoos.config.JesoosConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Resolves the LLM provider for chat.
 *
 * The platform uses a single provider ({@code jesoos.llm.provider}, anthropic).
 * The brand parameters are kept so per-brand provider selection can be reintroduced
 * without touching call sites, but currently every brand resolves to the same provider.
 */
@ApplicationScoped
public class BrandLlmProviderResolver {

    private static final Logger LOGGER = Logger.getLogger(BrandLlmProviderResolver.class);

    @Inject
    JesoosConfig config;

    private LlmProviderAdapter adapter;
    private ChatLlmClient client;

    @PostConstruct
    void init() {
        adapter = LlmProviderRegistry.resolve(config.getLlmProvider());
        client = adapter.createClient(config);
        LOGGER.infof("[llm] provider=%s", adapter.providerId());
    }

    public LlmProviderAdapter adapterFor(String brandSlug) {
        return adapter;
    }

    public ChatLlmClient clientFor(String brandSlug) {
        return client;
    }

    public String modelFor(String brandSlug, LlmUseCase useCase) {
        return adapter.modelFor(useCase);
    }
}
