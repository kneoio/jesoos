package com.semantyca.jesoos.service.live;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.external.ElevenLabsClient;
import com.semantyca.jesoos.external.GCPTTSClient;
import com.semantyca.jesoos.external.ModelslabClient;
import com.semantyca.jesoos.external.TextToSpeechClient;
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
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.semantyca.mixpla.model.aiagent.Voice;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class IntroTtsGenerator {
    private static final Logger LOGGER = Logger.getLogger(IntroTtsGenerator.class);

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
        
        try {
            Path uploadsDir = Path.of(config.getPathUploads()).toAbsolutePath().resolve("intro-tts").resolve("temp");
            Files.createDirectories(uploadsDir);
            LOGGER.infof("Intro TTS temp directory initialized: %s", uploadsDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create intro-tts temp directory", e);
            throw new RuntimeException("Failed to initialize intro-tts temp directory", e);
        }
    }

    public Uni<IntroAudioResult> generateIntroAudioFile(
            LiveScene liveScene,
            SongEntry songEntry,
            AiAgent agent,
            IStream stream,
            LanguageTag language
    ) {

        UUID selectedPromptId = songEntry.getPromptEntry().getPromptId();
        AtomicBoolean fallBacked = new AtomicBoolean(false);
        
        return promptService.getById(selectedPromptId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == language) { //if it is ENG
                        return Uni.createFrom().item(masterPrompt);
                    }
                    return promptService
                            .findByLanguage(selectedPromptId, language)
                            .map(p -> {
                                if (p != null) {
                                    return p;
                                } else {
                                    fallBacked.set(true);
                                    return masterPrompt;
                                }
                            });
                })
                .chain(prompt -> generateDraftText(prompt, songEntry.getSoundFragment(), agent, stream)
                        .map(draftContent -> new PromptAndDraft(prompt, draftContent)))
                .chain(tuple -> generateSpokenText(tuple.prompt(), tuple.draftContent(), liveScene.getTraceId(), stream.getSlugName()))
                .chain(spokenText -> generateTtsAudio(spokenText, agent, language, liveScene.getSceneTitle(), liveScene.getTraceId(), stream.getSlugName()))
                .chain(v -> calculateDuration(v, language, fallBacked.get()));
    }

    public Uni<String> generateTtsAudio(String text, AiAgent agent, LanguageTag language, String sceneTitle, UUID traceId, String brandName) {
        return generateTtsAudio(text, agent.getTtsSetting().getDj(), language, sceneTitle, traceId, brandName);
    }

    public Uni<String> generateTtsAudio(String text, Voice voice, LanguageTag language, String sceneTitle, UUID traceId, String brandName) {
        String voiceId = voice.getId();
        TTSEngineType engineType = voice.getEngineType();

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

    private Uni<IntroAudioResult> calculateDuration(String filePath, LanguageTag languageTag, boolean fallBacked) {
        return Uni.createFrom().item(() -> {
            try {
                FFmpegProbeResult probeResult =
                        ffmpegProvider.getFFprobe().probe(filePath);
                double durationSeconds = probeResult.getFormat().duration;
                int roundedDuration = (int) Math.ceil(durationSeconds);
                LOGGER.infof("Intro audio duration: %s seconds (file: %s)", roundedDuration, filePath);
                return new IntroAudioResult(filePath, roundedDuration, languageTag, fallBacked);
            } catch (Exception e) {
                LOGGER.warnf("Failed to probe intro audio duration for %s, using default 10s", filePath, e);
                return new IntroAudioResult(filePath, 10, languageTag, fallBacked);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}