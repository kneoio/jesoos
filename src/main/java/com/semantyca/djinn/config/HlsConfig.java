package com.semantyca.djinn.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "hls")
public interface HlsConfig {
    
    @WithName("segment.duration")
    @WithDefault("6")
    int getSegmentDuration();
    
    @WithName("max.visible.segments")
    @WithDefault("20")
    int getMaxVisibleSegments();
    
    @WithName("bitrates")
    @WithDefault("128000,64000")
    String getBitrates();
    
    @WithName("target.duration")
    @WithDefault("6")
    int getTargetDuration();
}
