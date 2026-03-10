package com.semantyca.djinn.service.stream;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.agent.ElevenLabsClient;
import com.semantyca.djinn.agent.GCPTTSClient;
import com.semantyca.djinn.agent.ModelslabClient;
import com.semantyca.djinn.agent.TextToSpeechClient;
import com.semantyca.djinn.config.DjinnConfig;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.service.PromptService;
import com.semantyca.djinn.service.live.scripting.DraftFactory;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
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
    DjinnConfig config;

    private final Random random = new Random();

    public Uni<String> generateIntroAudioFile(
            LiveScene scene,
            SoundFragment song,
            AiAgent agent,
            IStream stream,
            LanguageTag broadcastingLanguage
    ) {
        List<ScenePrompt> introPrompts = scene.getIntroPrompts();
        if (introPrompts == null || introPrompts.isEmpty()) {
            return Uni.createFrom().failure(
                new IllegalStateException("Scene has no intro prompts: " + scene.getSceneTitle())
            );
        }

        List<UUID> enabledPromptIds = introPrompts.stream()
                .filter(ScenePrompt::isActive)
                .map(ScenePrompt::getPromptId)
                .toList();

        if (enabledPromptIds.isEmpty()) {
            return Uni.createFrom().failure(
                new IllegalStateException("Scene has no enabled intro prompts: " + scene.getSceneTitle())
            );
        }

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
                .chain(prompt -> generateDraftText(prompt, song, agent, stream))
                .chain(draftText -> generateTtsAudio(draftText, agent, broadcastingLanguage, scene.getSceneTitle()));
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
            String finalText = prompt.getPrompt() + "\n\n" + draft;
            LOGGER.info("Generated intro draft text (length: {} chars)", finalText.length());
            return finalText;
        });
    }

    private Uni<String> generateTtsAudio(String text, AiAgent agent, LanguageTag language, String sceneTitle) {
        String voiceId = agent.getTtsSetting().getDj().getId();
        TTSEngineType engineType = agent.getTtsSetting().getDj().getEngineType();

        TextToSpeechClient ttsClient;
        String modelId;

        if (engineType == TTSEngineType.MODELSLAB) {
            ttsClient = modelslabClient;
            modelId = null;
            LOGGER.info("Using Modelslab TTS for scene '{}'", sceneTitle);
        } else if (engineType == TTSEngineType.GOOGLE) {
            ttsClient = gcpttsClient;
            modelId = null;
            LOGGER.info("Using GCP TTS for scene '{}'", sceneTitle);
        } else {
            ttsClient = elevenLabsClient;
            modelId = config.getElevenLabsModelId();
            LOGGER.info("Using ElevenLabs TTS for scene '{}' with model: {}", sceneTitle, modelId);
        }

        return ttsClient.textToSpeech(text, voiceId, modelId, language)
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
