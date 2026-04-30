package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.external.AnthropicTextClient;
import com.semantyca.jesoos.external.ElevenLabsClient;
import com.semantyca.jesoos.external.FishAudioClient;
import com.semantyca.jesoos.external.GCPTTSClient;
import com.semantyca.jesoos.external.GroqTextClient;
import com.semantyca.jesoos.external.LlmTextClient;
import com.semantyca.jesoos.external.ModelslabClient;
import com.semantyca.jesoos.external.TTSClient;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.DjPrompt;
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

    private final PromptService promptService;
    private final DraftFactory draftFactory;
    private final ElevenLabsClient elevenLabsClient;
    private final ModelslabClient modelslabClient;
    private final FishAudioClient fishAudioClient;
    private final GCPTTSClient gcpttsClient;
    private final JesoosConfig config;
    private final FFmpegProvider ffmpegProvider;
    private final MetricPublisher metricPublisher;
    private final AnthropicTextClient anthropicTextClient;
    private final GroqTextClient groqTextClient;

    @Inject
    public IntroTtsGenerator(
            PromptService promptService,
            DraftFactory draftFactory,
            ElevenLabsClient elevenLabsClient,
            ModelslabClient modelslabClient,
            FishAudioClient fishAudioClient,
            GCPTTSClient gcpttsClient,
            JesoosConfig config,
            FFmpegProvider ffmpegProvider,
            MetricPublisher metricPublisher,
            AnthropicTextClient anthropicTextClient,
            GroqTextClient groqTextClient
    ) {
        this.promptService = promptService;
        this.draftFactory = draftFactory;
        this.elevenLabsClient = elevenLabsClient;
        this.modelslabClient = modelslabClient;
        this.fishAudioClient = fishAudioClient;
        this.gcpttsClient = gcpttsClient;
        this.config = config;
        this.ffmpegProvider = ffmpegProvider;
        this.metricPublisher = metricPublisher;
        this.anthropicTextClient = anthropicTextClient;
        this.groqTextClient = groqTextClient;
    }

    @PostConstruct
    void initIntroTtsTempDir() {
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
                .chain(v -> calculateDuration(v, language, fallBacked.get(), agent.getTtsSetting().getDj().getGain()));
    }

    public Uni<IntroAudioResult> generateCustomIntroAudioFile(
            String customIntroText,
            AiAgent agent,
            LanguageTag language,
            String sceneTitle,
            UUID traceId,
            String brandName
    ) {
        LOGGER.infof("Generating custom intro audio for scene '%s' with text: '%s'", sceneTitle, customIntroText);
        
        return generateTtsAudio(customIntroText, agent, language, sceneTitle, traceId, brandName)
                .chain(filePath -> calculateDuration(filePath, language, false, agent.getTtsSetting().getDj().getGain()));
    }

    public Uni<String> generateTtsAudio(String text, AiAgent agent, LanguageTag language, String sceneTitle, UUID traceId, String brandName) {
        return generateTtsAudio(text, agent.getTtsSetting().getDj(), language, sceneTitle, traceId, brandName);
    }

    public Uni<String> generateTtsAudio(String text, Voice voice, LanguageTag language, String sceneTitle, UUID traceId, String brandName) {
        String voiceId = voice.getId();
        TTSEngineType engineType = voice.getEngineType();

        TTSClient ttsClient;
        String modelId;
        String finalText = text;

        String beforeHr = text.contains("---") ? text.substring(0, text.indexOf("---")) : text;
        String trimmed = beforeHr.replaceAll("(?m)^#+\\s.*$", "").replaceAll("\\[.*?]", "").replaceAll("\n{3,}", "\n\n").replace("*", "").trim();
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
        } else if (engineType == TTSEngineType.FISH_AUDIO) {
            ttsClient = fishAudioClient;
            modelId = config.getFishAudioModelId();
            finalText = trimmed;
            LOGGER.infof("Using Fish Audio TTS for scene '%s'", sceneTitle);
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

    private Uni<String> generateDraftText(DjPrompt prompt, SoundFragment song, AiAgent agent, IStream stream) {
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

    private Uni<String> generateSpokenText(DjPrompt prompt, String draftContent, UUID traceId, String brandName) {
        if (draftContent.contains("\"error\":") || draftContent.contains("Search failed")) {
            LOGGER.errorf("Draft content contains error, skipping generation: %s", draftContent);
            return Uni.createFrom().item((String) null);
        }

        String fullPrompt = String.format(
                "%s\n\nDraft input:\n%s",
                prompt.getPrompt(),
                draftContent
        );

        long maxTokens = 2048L;
        String provider = config.getIntroTtsLlmProvider();
        String model = "groq".equals(provider) ? config.getIntroTtsGroqModel() : config.getIntroTtsAnthropicModel();
        LlmTextClient llmTextClient = selectLlmClient(provider);
        return llmTextClient.createTextMessage(
                        model,
                        maxTokens,
                        getSystemPrompt(),
                        fullPrompt)
                .map(response -> {
                    LOGGER.infof("Claude response received - Input tokens: %s, Output tokens: %s",
                            response.inputTokens(), response.outputTokens());

                    String text = response.text();
                    if (response.outputTokens() >= maxTokens * 0.95) {
                        LOGGER.warnf("Content generation used %s tokens (%s%% of max %s). Response may be truncated.",
                                response.outputTokens(),
                                Math.round((response.outputTokens() / (double) maxTokens) * 100),
                                maxTokens);
                    }

                    if (text.contains("technical difficulty")
                            || text.contains("technical error")
                            || text.contains("technical issue")) {
                        metricPublisher.publishMetric(brandName, MetricEventType.WARNING, ProcessType.FLOW, "intro_spoken_text_generation_failed",
                                Map.of("reason", "technical_difficulty_detected", "promptId", prompt.getId().toString()), traceId);
                        return null;
                    }

                    LOGGER.infof("Generated text (%s tokens): %s", response.outputTokens(), text);
                    metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.FLOW, "intro_spoken_text_generated",
                            Map.of("inputTokens", response.inputTokens(), "outputTokens", response.outputTokens(),
                                    "promptId", prompt.getId().toString(), "promptTitle", prompt.getTitle(), "promptText", prompt.getPrompt(), "draft", draftContent, "spokenText", text,
                                    "llmProvider", provider, "llmModel", model), traceId);
                    return text;
                })
                .onFailure().invoke(e -> {
                    LOGGER.errorf("Anthropic API call failed - Type: %s, Message: %s", e.getClass().getSimpleName(), e.getMessage(), e);
                    metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_spoken_text_generation_failed",
                            Map.of("error", e.getMessage(), "errorType", e.getClass().getSimpleName(), "promptId", prompt.getId().toString()), traceId);
                });
    }

    private LlmTextClient selectLlmClient(String provider) {
        return "groq".equals(provider) ? groqTextClient : anthropicTextClient;
    }

    private String getSystemPrompt() {
        return "You are a professional radio DJ. CRITICAL: Use ONLY song information from 'Draft input:'. " +
                "NEVER use song names from PAST CONTEXT.";
    }

    private Uni<IntroAudioResult> calculateDuration(String filePath, LanguageTag languageTag, boolean fallBacked, float gain) {
        return Uni.createFrom().item(() -> {
            try {
                FFmpegProbeResult probeResult =
                        ffmpegProvider.getFFprobe().probe(filePath);
                double durationSeconds = probeResult.getFormat().duration;
                int roundedDuration = (int) Math.ceil(durationSeconds);
                LOGGER.infof("Intro audio duration: %s seconds (file: %s)", roundedDuration, filePath);
                return new IntroAudioResult(filePath, roundedDuration, languageTag, fallBacked, gain);
            } catch (Exception e) {
                LOGGER.warnf("Failed to probe intro audio duration for %s, using default 10s", filePath, e);
                return new IntroAudioResult(filePath, 10, languageTag, fallBacked, gain);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}