package com.semantyca.jesoos.service.live.generated;

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
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.Voice;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

public abstract class AbstractGeneratedContentService implements IGeneratedContent {
    private static final Logger LOGGER = Logger.getLogger(AbstractGeneratedContentService.class);

    public record AudioGenerationResult(String filePath, int durationSeconds) {}

    protected final PromptService promptService;
    protected final SoundFragmentService soundFragmentService;
    protected final ElevenLabsClient elevenLabsClient;
    protected final ModelslabClient modelslabClient;
    protected final GCPTTSClient gcpttsClient;
    protected final JesoosConfig config;
    protected final DraftFactory draftFactory;
    protected final AiAgentService aiAgentService;
    protected final SoundFragmentRepository soundFragmentRepository;
    protected final FFmpegProvider ffmpegProvider;
    protected AnthropicClient anthropicClient;

    protected AbstractGeneratedContentService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            ElevenLabsClient elevenLabsClient,
            ModelslabClient modelslabClient,
            GCPTTSClient gcpttsClient,
            JesoosConfig config,
            DraftFactory draftFactory,
            AiAgentService aiAgentService,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider ffmpegProvider
    ) {
        this.promptService = promptService;
        this.soundFragmentService = soundFragmentService;
        this.elevenLabsClient = elevenLabsClient;
        this.modelslabClient = modelslabClient;
        this.gcpttsClient = gcpttsClient;
        this.config = config;
        this.draftFactory = draftFactory;
        this.aiAgentService = aiAgentService;
        this.soundFragmentRepository = soundFragmentRepository;
        this.ffmpegProvider = ffmpegProvider;
    }

    protected void initAnthropicClient() {
        anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }

    protected abstract PlaylistItemType getContentType();
    protected abstract Voice getVoice(AiAgent agent);
    protected abstract String getSystemPrompt();

    public Uni<AudioGenerationResult> generateAudio(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            LanguageTag airLanguage,
            String sceneTitle,
            UUID traceId
    ) {
        return promptService.getById(promptId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == airLanguage) {
                        return Uni.createFrom().item(masterPrompt);
                    }
                    return promptService.findByLanguage(promptId, airLanguage)
                            .map(p -> p != null ? p : masterPrompt);
                })
                .chain(prompt -> generateText(prompt, agent, stream))
                .chain(text -> {
                    if (text == null) {
                        return Uni.createFrom().failure(
                                new RuntimeException("Text generation failed for scene: " + sceneTitle));
                    }
                    return generateTtsAndSave(text, agent, airLanguage, sceneTitle);
                });
    }

    private Uni<String> generateText(Prompt prompt, AiAgent agent, IStream stream) {
        return draftFactory.createDraft(null, agent, stream, prompt.getDraftId(), LanguageTag.EN_US, new HashMap<>())
                .chain(draftContent -> Uni.createFrom().item(() -> {
                    if (draftContent.contains("\"error\":") || draftContent.contains("Search failed")) {
                        LOGGER.errorf("Draft content contains error, skipping: %s", draftContent);
                        return null;
                    }

                    String fullPrompt = prompt.getPrompt() + "\n\nDraft input:\n" + draftContent;
                    long maxTokens = 2048L;

                    MessageCreateParams params = MessageCreateParams.builder()
                            .model(Model.CLAUDE_HAIKU_4_5_20251001)
                            .maxTokens(maxTokens)
                            .system(getSystemPrompt())
                            .addUserMessage(fullPrompt)
                            .build();

                    try {
                        Message response = anthropicClient.messages().create(params);
                        String text = response.content().stream()
                                .filter(ContentBlock::isText)
                                .map(block -> block.asText().text())
                                .findFirst()
                                .orElseThrow(() -> new RuntimeException("No text generated from AI"));

                        if (text.contains("technical difficulty")
                                || text.contains("technical error")
                                || text.contains("technical issue")) {
                            LOGGER.warnf("Generated text signals technical error, skipping");
                            return null;
                        }

                        LOGGER.infof("Generated text (%d tokens): %s",
                                response.usage().outputTokens(), text);
                        return text;
                    } catch (Exception e) {
                        LOGGER.errorf("Anthropic API call failed: %s", e.getMessage(), e);
                        throw e;
                    }
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    }

    private Uni<AudioGenerationResult> generateTtsAndSave(
            String text, AiAgent agent, LanguageTag language, String sceneTitle) {

        Voice voice = getVoice(agent);
        if (voice == null) {
            return Uni.createFrom().failure(
                    new RuntimeException("Voice not configured for " + getContentType()));
        }

        TextToSpeechClient ttsClient;
        String modelId;
        String trimmed = text.replaceAll("\\[.*?]", "").replaceAll("\n{3,}", "\n\n").replace("*", "").trim();

        if (voice.getEngineType() == TTSEngineType.MODELSLAB) {
            ttsClient = modelslabClient;
            modelId = null;
        } else if (voice.getEngineType() == TTSEngineType.GOOGLE) {
            ttsClient = gcpttsClient;
            modelId = null;
        } else {
            ttsClient = elevenLabsClient;
            modelId = config.getElevenLabsModelId();
        }

        return ttsClient.textToSpeech(trimmed, voice.getId(), modelId, language)
                .chain(audioBytes -> Uni.createFrom().item(() -> {
                    try {
                        Path uploadsDir = Path.of(config.getPathUploads())
                                .toAbsolutePath()
                                .resolve("generated-content")
                                .resolve("temp");
                        Files.createDirectories(uploadsDir);

                        String fileName = "generated_" + UUID.randomUUID() + ".mp3";
                        Path filePath = uploadsDir.resolve(fileName);
                        Files.write(filePath, audioBytes);

                        LOGGER.infof("Generated content TTS saved: %s (%d bytes)", filePath, audioBytes.length);

                        try {
                            FFmpegProbeResult probe =
                                    ffmpegProvider.getFFprobe().probe(filePath.toString());
                            int duration = (int) Math.ceil(probe.getFormat().duration);
                            return new AudioGenerationResult(filePath.toString(), duration);
                        } catch (Exception e) {
                            LOGGER.warnf("Failed to probe duration for %s, using default 30s", filePath);
                            return new AudioGenerationResult(filePath.toString(), 30);
                        }
                    } catch (IOException e) {
                        LOGGER.errorf("Failed to save TTS audio for scene '%s'", sceneTitle, e);
                        throw new RuntimeException("Failed to save TTS audio", e);
                    }
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    }
}
