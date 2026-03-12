package com.semantyca.jesoos.service.stream;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.agent.ElevenLabsClient;
import com.semantyca.jesoos.agent.GCPTTSClient;
import com.semantyca.jesoos.agent.ModelslabClient;
import com.semantyca.jesoos.agent.TextToSpeechClient;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
public class IntroTtsGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(IntroTtsGenerator.class);

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

    private final Random random = new Random();
    private AnthropicClient anthropicClient;

    @PostConstruct
    void init() {
        anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }

    public Uni<String> generateIntroAudioFile(
            LiveScene scene,
            SoundFragment song,
            AiAgent agent,
            IStream stream,
            LanguageTag broadcastingLanguage
    ) {
        List<ScenePrompt> introPrompts = scene.getIntroPrompts();
        List<UUID> enabledPromptIds = introPrompts.stream()
                .filter(ScenePrompt::isActive)
                .map(ScenePrompt::getPromptId)
                .toList();

        UUID selectedPromptId = enabledPromptIds.get(random.nextInt(enabledPromptIds.size()));

        return promptService.getById(selectedPromptId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == broadcastingLanguage) {
                        return Uni.createFrom().item(masterPrompt);
                    }
                    return promptService
                            .findByMasterAndLanguage(selectedPromptId, broadcastingLanguage, false)
                            .map(p -> p != null ? p : masterPrompt);
                })
                .chain(prompt -> generateDraftText(prompt, song, agent, stream)
                        .map(draftContent -> new PromptAndDraft(prompt, draftContent)))
                .chain(tuple -> generateSpokenText(tuple.prompt(), tuple.draftContent(), agent, stream, broadcastingLanguage))
                .chain(spokenText -> generateTtsAudio(spokenText, agent, broadcastingLanguage, scene.getSceneTitle()));
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
            LOGGER.info("Draft content received: {}", draft);
            return draft;
        });
    }

    private Uni<String> generateSpokenText(Prompt prompt, String draftContent, AiAgent agent, IStream stream, LanguageTag broadcastingLanguage) {
        return Uni.createFrom().<String>emitter(em -> {
            if (draftContent.contains("\"error\":") || draftContent.contains("Search failed")) {
                LOGGER.error("Draft content contains error, skipping generation: {}", draftContent);
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

                LOGGER.info("Claude response received - Input tokens: {}, Output tokens: {}",
                        response.usage().inputTokens(), response.usage().outputTokens());

                String text = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(block -> block.asText().text())
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("No text generated from AI"));

                if (response.usage().outputTokens() >= maxTokens * 0.95) {
                    LOGGER.warn("Content generation used {} tokens ({}% of max {}). Response may be truncated.",
                            response.usage().outputTokens(),
                            Math.round((response.usage().outputTokens() / (double) maxTokens) * 100),
                            maxTokens);
                }

                if (text.contains("technical difficulty")
                        || text.contains("technical error")
                        || text.contains("technical issue")) {
                    em.complete(null);
                } else {
                    LOGGER.info("Generated text ({} tokens): {}", response.usage().outputTokens(), text);
                    em.complete(text);
                }
            } catch (Exception e) {
                LOGGER.error("Anthropic API call failed - Type: {}, Message: {}", e.getClass().getSimpleName(), e.getMessage(), e);
                em.fail(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private String getSystemPrompt() {
        return "You are a professional radio DJ. CRITICAL: Use ONLY song information from 'Draft input:'. " +
                "NEVER use song names from PAST CONTEXT.";
    }

    private Uni<String> generateTtsAudio(String text, AiAgent agent, LanguageTag language, String sceneTitle) {
        String voiceId = agent.getTtsSetting().getDj().getId();
        TTSEngineType engineType = agent.getTtsSetting().getDj().getEngineType();

        TextToSpeechClient ttsClient;
        String modelId;
        String finalText = text;

        String trimmed = text.replaceAll("\\[.*?]", "").replaceAll("\\n{3,}", "\n\n").trim();
        if (engineType == TTSEngineType.MODELSLAB) {
            ttsClient = modelslabClient;
            modelId = null;
            finalText = trimmed;
            LOGGER.info("Using Modelslab TTS for scene '{}' (cleaned tags)", sceneTitle);
        } else if (engineType == TTSEngineType.GOOGLE) {
            ttsClient = gcpttsClient;
            modelId = null;
            finalText = trimmed;
            LOGGER.info("Using GCP TTS for scene '{}' (cleaned tags)", sceneTitle);
        } else {
            ttsClient = elevenLabsClient;
            modelId = config.getElevenLabsModelId();
            LOGGER.info("Using ElevenLabs TTS for scene '{}' with model: {}", sceneTitle, modelId);
        }

        return ttsClient.textToSpeech(finalText, voiceId, modelId, language)
                .map(audioBytes -> {
                    try {
                        Path uploadsDir = Paths.get(config.getPathUploads(), "intro-tts", "temp");
                        Files.createDirectories(uploadsDir);

                        String fileName = "intro_" + UUID.randomUUID() + ".mp3";
                        Path audioFilePath = uploadsDir.resolve(fileName);
                        Files.write(audioFilePath, audioBytes);

                        LOGGER.info("Intro TTS audio saved: {} ({} bytes)", audioFilePath, audioBytes.length);
                        return audioFilePath.toString();
                    } catch (IOException e) {
                        LOGGER.error("Failed to save TTS audio for scene '{}'", sceneTitle, e);
                        throw new RuntimeException("Failed to save TTS audio", e);
                    }
                });
    }
}