package com.semantyca.djinn.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "radio.main")
public interface MainConfig {
    
    @WithName("basedir")
    @WithDefault("uploads")
    String getBaseDir();
    
    @WithName("temp-dir")
    @WithDefault("temp")
    String getTempDir();
}
