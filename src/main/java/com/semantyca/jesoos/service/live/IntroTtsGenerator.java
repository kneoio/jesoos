package com.semantyca.jesoos.service.live;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.agent.ElevenLabsClient;
import com.semantyca.jesoos.agent.GCPTTSClient;
import com.semantyca.jesoos.agent.ModelslabClient;
import com.semantyca.jesoos.agent.TextToSpeechClient;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class IntroTtsGenerator {
    private static final Logger LOGGER = Logger.getLogger(IntroTtsGenerator.class);

    public record IntroAudioResult(String filePath, int durationSeconds) {}

    private record PromptAndDraft(Prompt prompt, String draftContent) {}

    @Inject
    PromptService promptService;
    @Inject
    DraftFactory draftFactory;
    @Inject
    ElevenLabsClient elevenLabsClient;
    @Inject
    ModelslabClient modelslabClient;
    @Inject
    GCPTTSClient gcpttsClient;
    @Inject
    JesoosConfig config;
    @Inject
    FFmpegProvider ffmpegProvider;
    @Inject
    private MetricPublisher metricPublisher;
    private AnthropicClient anthropicClient;

    @PostConstruct
    void init() {
        anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }

    public Uni<IntroAudioResult> generateIntroAudioFile(
            LiveScene scene,
            SongEntry songEntry,
            AiAgent agent,
            IStream stream,
            LanguageTag broadcastingLanguage
    ) {

        UUID selectedPromptId = songEntry.getPromptEntry().getPromptId();

        return promptService.getById(selectedPromptId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == broadcastingLanguage) {
                        return Uni.createFrom().item(masterPrompt);
                    }
                    return promptService
                            .findByMasterAndLanguage(selectedPromptId, broadcastingLanguage, false)
                            .map(p -> p != null ? p : masterPrompt);
                })
                .chain(prompt -> generateDraftText(prompt, songEntry.getSoundFragment(), agent, stream)
                        .map(draftContent -> new PromptAndDraft(prompt, draftContent)))
                .chain(tuple -> generateSpokenText(tuple.prompt(), tuple.draftContent(), scene.getTraceId(), stream.getSlugName()))
                .chain(spokenText -> generateTtsAudio(spokenText, agent, broadcastingLanguage, scene.getSceneTitle(), scene.getTraceId(), stream.getSlugName()))
                .chain(this::calculateDuration);
    }

    private Uni<String> generateDraftText(Prompt prompt, SoundFragment song, AiAgent agent, IStream stream) {
        return draftFactory.createDraft(
                song,
                agent,
                stream,
                prompt.getDraftId(),
                LanguageTag.EN_US,
                new HashMap<>()
        ).map(draft -> {
            LOGGER.infof("Draft content received: %s", draft);
            return draft;
        });
    }

    private Uni<String> generateSpokenText(Prompt prompt, String draftContent, UUID traceId, String brandName) {
        return Uni.createFrom().<String>emitter(em -> {
            if (draftContent.contains("\"error\":") || draftContent.contains("Search failed")) {
                LOGGER.errorf("Draft content contains error, skipping generation: %s", draftContent);
                em.complete(null);
                return;
            }

            String fullPrompt = String.format(
                    "%s\n\nDraft input:\n%s",
                    prompt.getPrompt(),
                    draftContent
            );

            // LOGGER.info("Sending prompt to Claude (length: {} chars)", fullPrompt.length());

            long maxTokens = 2048L;
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5_20251001)
                    .maxTokens(maxTokens)
                    .system(getSystemPrompt())
                    .addUserMessage(fullPrompt)
                    .build();

            try {
                Message response = anthropicClient.messages().create(params);

                LOGGER.infof("Claude response received - Input tokens: %s, Output tokens: %s",
                        response.usage().inputTokens(), response.usage().outputTokens());

                String text = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(block -> block.asText().text())
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("No text generated from AI"));

                if (response.usage().outputTokens() >= maxTokens * 0.95) {
                    LOGGER.warnf("Content generation used %s tokens (%s% of max %s). Response may be truncated.",
                            response.usage().outputTokens(),
                            Math.round((response.usage().outputTokens() / (double) maxTokens) * 100),
                            maxTokens);
                }

                if (text.contains("technical difficulty")
                        || text.contains("technical error")
                        || text.contains("technical issue")) {
                    metricPublisher.publishMetric(brandName, MetricEventType.WARNING, ProcessType.FLOW,"intro_spoken_text_generation_failed",
                            Map.of("reason", "technical_difficulty_detected", "promptId", prompt.getId().toString()), traceId);
                    em.complete(null);
                } else {
                    LOGGER.infof("Generated text (%s tokens): %s", response.usage().outputTokens(), text);
                    metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.FLOW, "intro_spoken_text_generated",
                            Map.of("inputTokens", response.usage().inputTokens(), "outputTokens", response.usage().outputTokens(),
                                    "promptId", prompt.getId().toString()), traceId);
                    em.complete(text);
                }
            } catch (Exception e) {
                LOGGER.errorf("Anthropic API call failed - Type: %s, Message: %s", e.getClass().getSimpleName(), e.getMessage(), e);
                metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_spoken_text_generation_failed",
                        Map.of("error", e.getMessage(), "errorType", e.getClass().getSimpleName(), "promptId", prompt.getId().toString()), traceId);
                em.fail(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private String getSystemPrompt() {
        return "You are a professional radio DJ. CRITICAL: Use ONLY song information from 'Draft input:'. " +
                "NEVER use song names from PAST CONTEXT.";
    }

    private Uni<String> generateTtsAudio(String text, AiAgent agent, LanguageTag language, String sceneTitle, UUID traceId, String brandName) {
        String voiceId = agent.getTtsSetting().getDj().getId();
        TTSEngineType engineType = agent.getTtsSetting().getDj().getEngineType();

        TextToSpeechClient ttsClient;
        String modelId;
        String finalText = text;

        String trimmed = text.replaceAll("\\[.*?]", "").replaceAll("\n{3,}", "\n\n").replace("*", "").trim();
        if (engineType == TTSEngineType.MODELSLAB) {
            ttsClient = modelslabClient;
            modelId = null;
            finalText = trimmed;
            LOGGER.infof("Using Modelslab TTS for scene '%s' (cleaned tags)", sceneTitle);
        } else if (engineType == TTSEngineType.GOOGLE) {
            ttsClient = gcpttsClient;
            modelId = null;
            finalText = trimmed;
            LOGGER.infof("Using GCP TTS for scene '%s' (cleaned tags)", sceneTitle);
        } else {
            ttsClient = elevenLabsClient;
            modelId = config.getElevenLabsModelId();
            LOGGER.infof("Using ElevenLabs TTS for scene '%s' with model: %s", sceneTitle, modelId);
        }

        return ttsClient.textToSpeech(finalText, voiceId, modelId, language)
                .map(audioBytes -> {
                    try {
                        Path uploadsDir = Path.of(config.getPathUploads()).toAbsolutePath().resolve("intro-tts").resolve("temp");
                        Files.createDirectories(uploadsDir);

                        String fileName = "intro_" + UUID.randomUUID() + ".mp3";
                        Path audioFilePath = uploadsDir.resolve(fileName);
                        Files.write(audioFilePath, audioBytes);

                        LOGGER.infof("Intro TTS audio saved: %s (%s bytes)", audioFilePath, audioBytes.length);
                        metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.FLOW, "intro_tts_audio_generated",
                                Map.of("engineType", engineType.toString(), "sceneTitle", sceneTitle,
                                        "audioSize", audioBytes.length, "textLength", text.length()), traceId);
                        return audioFilePath.toString();
                    } catch (IOException e) {
                        LOGGER.error("Failed to save TTS audio for scene '{}'", sceneTitle, e);
                        metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_tts_audio_save_failed",
                                Map.of("error", e.getMessage(), "sceneTitle", sceneTitle, "engineType", engineType.toString()), traceId);
                        throw new RuntimeException("Failed to save TTS audio", e);
                    }
                })
                .onFailure().invoke(e -> {
                    LOGGER.error("TTS generation failed for scene '{}'", sceneTitle, e);
                    metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW,"intro_tts_audio_generation_failed",
                            Map.of("error", e.getMessage(), "sceneTitle", sceneTitle, "engineType", engineType.toString()), traceId);
                });
    }

    private Uni<IntroAudioResult> calculateDuration(String filePath) {
        return Uni.createFrom().item(() -> {
            try {
                net.bramp.ffmpeg.probe.FFmpegProbeResult probeResult =
                        ffmpegProvider.getFFprobe().probe(filePath);
                double durationSeconds = probeResult.getFormat().duration;
                int roundedDuration = (int) Math.ceil(durationSeconds);
                LOGGER.infof("Intro audio duration: %s seconds (file: %s)", roundedDuration, filePath);
                return new IntroAudioResult(filePath, roundedDuration);
            } catch (Exception e) {
                LOGGER.warnf("Failed to probe intro audio duration for %s, using default 10s", filePath, e);
                return new IntroAudioResult(filePath, 10);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}