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
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.IntroAudioResult;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.Scene;
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
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractGeneratedContentService implements IGeneratedContent {
    private static final Logger LOGGER = Logger.getLogger(AbstractGeneratedContentService.class);

    protected final PromptService promptService;
    protected final SoundFragmentService soundFragmentService;
    protected final ElevenLabsClient elevenLabsClient;
    protected final ModelslabClient modelslabClient;
    protected final GCPTTSClient gcpttsClient;
    protected final IntroTtsGenerator introTtsGenerator;
    protected final JesoosConfig config;
    protected final DraftFactory draftFactory;
    protected final AiAgentService aiAgentService;
    protected AnthropicClient anthropicClient;

    protected AbstractGeneratedContentService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            ElevenLabsClient elevenLabsClient,
            ModelslabClient modelslabClient,
            GCPTTSClient gcpttsClient,
            IntroTtsGenerator introTtsGenerator,
            JesoosConfig config,
            DraftFactory draftFactory,
            AiAgentService aiAgentService
    ) {
        this.promptService = promptService;
        this.soundFragmentService = soundFragmentService;
        this.elevenLabsClient = elevenLabsClient;
        this.modelslabClient = modelslabClient;
        this.gcpttsClient = gcpttsClient;
        this.introTtsGenerator = introTtsGenerator;
        this.config = config;
        this.draftFactory = draftFactory;
        this.aiAgentService = aiAgentService;
    }

    protected void initAnthropicClient() {
        anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }

    protected abstract String getSystemPrompt();

    public Uni<IntroAudioResult> generateAudio(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            LanguageTag airLanguage,
            LiveScene liveScene
    ) {
        AtomicBoolean fallBacked = new AtomicBoolean(false);
        return promptService.getById(promptId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == airLanguage) {
                        return Uni.createFrom().item(masterPrompt);
                    }
                    return promptService.findByLanguage(promptId, airLanguage)
                            .map(p -> {
                                if (p != null) {
                                    return p;
                                } else {
                                    fallBacked.set(true);
                                    return masterPrompt;
                                }
                            });
                })
                .chain(prompt -> generateText(prompt, agent, stream))
                .chain(text -> {
                    if (text == null) {
                        return Uni.createFrom().failure(
                                new RuntimeException("Text generation failed for scene: " + liveScene.getSceneTitle()));
                    }
                    return introTtsGenerator.generateTtsAudio(text, agent, airLanguage, liveScene.getSceneTitle(), liveScene.getTraceId(), stream.getSlugName())
                            .map(filePath -> new IntroAudioResult(filePath, 0, airLanguage, fallBacked.get()));
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
}
