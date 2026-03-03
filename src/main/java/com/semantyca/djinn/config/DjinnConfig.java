package com.semantyca.djinn.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "djinn")
public interface DjinnConfig {

    @WithDefault("http://localhost:8080")
    String host();

    Path path();

    Ffmpeg ffmpeg();

    Ffprobe ffprobe();

    Segmentation segmentation();

    @WithName("station.whitelist")
    Optional<List<String>> stationWhitelist();

    interface Path {
        @WithDefault("uploads")
        String uploads();

        @WithDefault("temp")
        String temp();
    }

    interface Ffmpeg {
        @WithDefault("/usr/bin/ffmpeg")
        String path();
    }

    interface Ffprobe {
        @WithDefault("/usr/bin/ffprobe")
        String path();
    }

    interface Segmentation {
        Output output();

        interface Output {
            @WithDefault("temp/segments")
            String dir();
        }
    }
}