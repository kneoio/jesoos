package com.semantyca.djinn.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "worldnews")
public interface WorldNewsApiConfig {
    
    @WithName("api.key")
    String getApiKey();
    
    @WithName("base.url")
    @WithDefault("https://api.worldnewsapi.com")
    String getBaseUrl();
}
