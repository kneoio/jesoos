package com.semantyca.djinn.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "perplexity")
public interface PerplexityApiConfig {
    
    @WithName("api.key")
    String getApiKey();
    
    @WithName("base.url")
    @WithDefault("https://api.perplexity.ai")
    String getBaseUrl();
}
