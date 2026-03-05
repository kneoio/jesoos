package com.semantyca.djinn.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "djinn")
public interface DjinnConfig {
    @WithName("host")
    @WithDefault("localhost")
    String getHost();

    @WithName("agent.url")
    @WithDefault("http://localhost:38799")
    String getAgentUrl();

    @WithName("controller.upload.files.path")
    @WithDefault("controller-uploads")
    String getPathUploads();

    @WithName("merged.files.path")
    @WithDefault("merged")
    String getPathForMerged();

    @WithName("external.upload.files.path")
    @WithDefault("external_uploads")
    String getPathForExternalServiceUploads();

    @WithName("quarkus.file.upload.path")
    @WithDefault("/tmp/file-uploads")
    String getQuarkusFileUploadsPath();

    @WithName("ffmpeg.path")
    @WithDefault("ffmpeg")
    String getFfmpegPath();

    @WithName("ffprobe.path")
    @WithDefault("ffprobe")
    String getFfprobePath();

    @WithName("agent.api-key")
    String getAgentApiKey();

    @WithName("anthropic.api-key")
    String getAnthropicApiKey();

    @WithName("modelslab.api-key")
    String getModelslabApiKey();

    @WithName("google.credential-path")
    String getGcpCredentialsPath();

    @WithDefault("http://localhost:8080")
    String host();

    DjinnConfig.Path path();

    DjinnConfig.Ffmpeg ffmpeg();

    DjinnConfig.Ffprobe ffprobe();

    DjinnConfig.Segmentation segmentation();

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
        DjinnConfig.Segmentation.Output output();

        interface Output {
            @WithDefault("temp/segments")
            String dir();
        }
    }
}