package com.semantyca.jesoos.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

@ConfigMapping(prefix = "jesoos")
public interface JesoosConfig {
    @WithName("streamer")
    String getStreamerHost();

    @WithName("aivox.url")
    String getAivoxUrl();

    @WithName("spectra.url")
    String getSpectraUrl();

    @WithName("autostart.enabled")
    @WithDefault("true")
    boolean autostartEnabled();

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

    @WithName("chat.upload.max-body-size-bytes")
    @WithDefault("104857600")
    long getChatUploadMaxBodySizeBytes();

    @WithName("ffmpeg.path")
    @WithDefault("ffmpeg")
    String getFfmpegPath();

    @WithName("ffprobe.path")
    @WithDefault("ffprobe")
    String getFfprobePath();

    @WithName("anthropic.api-key")
    String getAnthropicApiKey();

    @WithName("llm.provider")
    @WithDefault("anthropic")
    String getLlmProvider();

    @WithName("groq.api-key")
    Optional<String> getGroqApiKey();

    @WithName("summary.llm.provider")
    @WithDefault("anthropic")
    String getSummaryLlmProvider();

    @WithName("summary.anthropic.model")
    @WithDefault("claude-haiku-4-5-20251001")
    String getSummaryAnthropicModel();

    @WithName("summary.groq.model")
    @WithDefault("llama-3.3-70b-versatile")
    String getSummaryGroqModel();

    @WithName("intro-tts.llm.provider")
    @WithDefault("anthropic")
    String getIntroTtsLlmProvider();

    @WithName("intro-tts.anthropic.model")
    @WithDefault("claude-haiku-4-5-20251001")
    String getIntroTtsAnthropicModel();

    @WithName("intro-tts.groq.model")
    @WithDefault("llama-3.3-70b-versatile")
    String getIntroTtsGroqModel();

    @WithName("generated.llm.provider")
    @WithDefault("anthropic")
    String getGeneratedLlmProvider();

    @WithName("generated.anthropic.model")
    @WithDefault("claude-haiku-4-5-20251001")
    String getGeneratedAnthropicModel();

    @WithName("generated.groq.model")
    @WithDefault("llama-3.3-70b-versatile")
    String getGeneratedGroqModel();

    @WithName("modelslab.api-key")
    String getModelslabApiKey();

    @WithName("google.credential-path")
    String getGcpCredentialsPath();

    @WithDefault("http://localhost:8080")
    String host();

    JesoosConfig.Path path();

    JesoosConfig.Ffmpeg ffmpeg();

    JesoosConfig.Ffprobe ffprobe();

    JesoosConfig.Segmentation segmentation();

    JesoosConfig.Keycloak keycloak();

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
        JesoosConfig.Segmentation.Output output();

        interface Output {
            @WithDefault("temp/segments")
            String dir();
        }
    }

    @WithName("elevenlabs.api-key")
    String getElevenLabsApiKey();

    @WithName("elevenlabs.model-id")
    @WithDefault("eleven_v3")
    String getElevenLabsModelId();

    @WithName("elevenlabs.output-format")
    @WithDefault("mp3_44100_128")
    String getElevenLabsOutputFormat();

    @WithName("fish.audio.api-key")
    String getFishAudioApiKey();

    @WithName("fish.audio.model.id")
    @WithDefault("s2-pro")
    String getFishAudioModelId();

    @WithName("mailer.from-address")
    @WithDefault("noreply@mixpla.io")
    String getFromAddress();

    @WithName("aivox-delay-seconds")
    @WithDefault("60")
    int getAivoxDelaySeconds();

    interface Keycloak {
        @WithName("url")
        String getUrl();

        @WithName("realm")
        String getRealm();

        @WithName("client-id")
        String getClientId();

        @WithName("client-secret")
        String getClientSecret();
    }

}