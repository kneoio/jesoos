package com.semantyca.jesoos.service.chat.llm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class LlmProviderRegistry {
    private static final Map<String, LlmProviderAdapter> ADAPTERS = buildAdapters();

    private LlmProviderRegistry() {}

    public static LlmProviderAdapter resolve(String providerRaw) {
        String provider = normalize(providerRaw);
        LlmProviderAdapter adapter = ADAPTERS.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported LLM provider '" + providerRaw
                    + "'. Supported providers: " + supportedProviders());
        }
        return adapter;
    }

    private static Map<String, LlmProviderAdapter> buildAdapters() {
        Map<String, LlmProviderAdapter> adapters = new LinkedHashMap<>();
        register(adapters, new AnthropicLlmProviderAdapter());
        register(adapters, new OpenAiLlmProviderAdapter());
        return Map.copyOf(adapters);
    }

    private static void register(Map<String, LlmProviderAdapter> adapters, LlmProviderAdapter adapter) {
        adapters.put(adapter.providerId().toLowerCase(Locale.ROOT), adapter);
    }

    private static String normalize(String providerRaw) {
        return providerRaw == null ? "" : providerRaw.trim().toLowerCase(Locale.ROOT);
    }

    private static String supportedProviders() {
        return ADAPTERS.keySet().stream().sorted().collect(Collectors.joining(", "));
    }
}
