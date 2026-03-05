package com.semantyca.djinn.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "weather")
public interface WeatherApiConfig {
    
    @WithName("api.key")
    String getApiKey();
    
    @WithName("base.url")
    @WithDefault("https://api.openweathermap.org/data/2.5")
    String getBaseUrl();
}
