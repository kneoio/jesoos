package com.semantyca.jesoos.service.live;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.semantyca.core.llm.AnthropicTextClient;
import com.semantyca.core.llm.GroqTextClient;
import com.semantyca.core.llm.LlmTextClient;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.external.*;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.CustomAction;
import com.semantyca.mixpla.model.DjPrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.Voice;
import com.semantyca.mixpla.model.cnst.LlmType;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.jesoos.model.stream.ILiveStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class IntroTtsGenerator {
    private static final Logger LOGGER = Logger.getLogger(IntroTtsGenerator.class);
    private static final Handlebars HANDLEBARS;
    private static final java.util.Random RANDOM = new java.util.Random();

    private JsonObject ttsFallbacks;
    private String introSystemPromptTemplate;
    private String introActionSystemPromptTemplate;

    static {
        HANDLEBARS = new Handlebars();
        HANDLEBARS.registerHelper("contains", (value, options) -> {
            if (value == null || options.param(0) == null) return false;
            return value.toString().contains(options.param(0).toString());
        });
        HANDLEBARS.registerHelper("random", (first, options) -> {
            java.util.List<Object> choices = new java.util.ArrayList<>();
            if (first != null) choices.add(first);
            for (Object p : options.params) if (p != null) choices.add(p);
            if (choices.isEmpty()) return "";
            return choices.get(RANDOM.nextInt(choices.size())).toString();
        });
    }

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
    private final BrandService brandService;
    private final MailService mailService;


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
            GroqTextClient groqTextClient,
            BrandService brandService,
            MailService mailService
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
        this.brandService = brandService;
        this.mailService = mailService;
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
        try {
            ttsFallbacks = new JsonObject(ResourceUtil.loadResourceAsString("tts-fallbacks.json"));
        } catch (Exception e) {
            LOGGER.warnf("Failed to load tts-fallbacks.json, using hardcoded default: %s", e.getMessage());
            ttsFallbacks = new JsonObject();
        }
        introSystemPromptTemplate = ResourceUtil.loadResourceAsString("prompts/introSystemPrompt.hbs");
        introActionSystemPromptTemplate = ResourceUtil.loadResourceAsString("prompts/introActionSystemPrompt.hbs");
    }

    public Uni<IntroAudioResult> generateIntroAudioFile(
            LiveScene liveScene,
            SongEntry songEntry,
            AiAgent agent,
            ILiveStream stream,
            LanguageTag language,
            int entrySeq,
            UUID entryTraceId
    ) {
        if (songEntry.getPromptEntry().isAction()) {
            return generateIntroFromAction(liveScene, songEntry, agent, stream, language, entrySeq, entryTraceId);
        }

        UUID selectedPromptId = songEntry.getPromptEntry().getPromptId();
        AtomicBoolean fallBacked = new AtomicBoolean(false);

        return promptService.resolveForLanguage(selectedPromptId, language)
                .chain(resolved -> {
                    fallBacked.set(resolved.fallBacked());
                    return generateDraftText(resolved.prompt(), songEntry.getSoundFragment(), songEntry.getSharerName(), agent, stream)
                            .map(draftContent -> new PromptAndDraft(resolved.prompt(), draftContent));
                })
                .chain(tuple -> generateSpokenText(tuple.prompt(), tuple.draftContent(), agent, language, entryTraceId, stream.getSlugName(), entrySeq))
                .chain(spokenText -> maybeSendContributorEmail(songEntry, spokenText).replaceWith(spokenText))
                .chain(spokenText -> generateTtsAudio(spokenText, agent, language, liveScene.getSceneTitle(), entryTraceId, stream.getSlugName(), entrySeq))
                .chain(v -> calculateDuration(v, language, fallBacked.get(), agent.getTtsSetting().getDj().getGain(), agent.getTtsSetting().getDj().getEngineType()));
    }

    /**
     * Emails the contributor once their song's DJ intro text is final, so "when will my song play"
     * comes with the actual intro rather than a generic placeholder. Best-effort: failures never break
     * the intro/TTS pipeline for the listener-facing stream.
     */
    private Uni<Void> maybeSendContributorEmail(SongEntry songEntry, String spokenText) {
        String email = songEntry.getContributorEmail();
        if (email == null || email.isBlank() || spokenText == null) {
            return Uni.createFrom().voidItem();
        }
        return mailService.sendContributionPlayingSoonAsync(email, songEntry.getSoundFragment().getTitle(), spokenText)
                .onFailure().recoverWithItem((Void) null);
    }

    private Uni<IntroAudioResult> generateIntroFromAction(
            LiveScene liveScene,
            SongEntry songEntry,
            AiAgent agent,
            ILiveStream stream,
            LanguageTag language,
            int entrySeq,
            UUID entryTraceId
    ) {
        CustomAction action = songEntry.getPromptEntry().getCustomAction();
        return draftFactory.buildActionContext(songEntry.getSoundFragment(), stream, action.getContextVars(), language, agent)
                .chain(ctx -> {
                    String rendered = renderHandlebars(action.getInstruction(), ctx);
                    return generateSpokenTextFromAction(rendered, action, ctx, agent, language, entryTraceId, stream.getSlugName(), entrySeq)
                            .chain(spokenText -> maybeSendDebugEmail(stream.getSlugName(), action.getName(), action.getInstruction(), ctx, spokenText)
                                    .replaceWith(spokenText));
                })
                .chain(spokenText -> generateTtsAudio(spokenText, agent, language, liveScene.getSceneTitle(), entryTraceId, stream.getSlugName(), entrySeq))
                .chain(v -> calculateDuration(v, language, false, agent.getTtsSetting().getDj().getGain(), agent.getTtsSetting().getDj().getEngineType()));
    }

    public Uni<IntroAudioResult> generateCustomIntroAudioFile(
            String customIntroText,
            AiAgent agent,
            LanguageTag language,
            String sceneTitle,
            UUID traceId,
            String brandName,
            int entrySeq
    ) {
        LOGGER.infof("Generating custom intro audio for scene '%s' with text: '%s'", sceneTitle, customIntroText);

        return generateTtsAudio(customIntroText, agent, language, sceneTitle, traceId, brandName, entrySeq)
                .chain(filePath -> calculateDuration(filePath, language, false, agent.getTtsSetting().getDj().getGain(), agent.getTtsSetting().getDj().getEngineType()));
    }

    public Uni<String> generateTtsAudio(String text, AiAgent agent, LanguageTag language, String sceneTitle, UUID traceId, String brandName, int entrySeq) {
        return generateTtsAudio(text, agent.getTtsSetting().getDj(), language, sceneTitle, traceId, brandName, entrySeq);
    }

    public Uni<String> generateTtsAudio(String text, Voice voice, LanguageTag language, String sceneTitle, UUID traceId, String brandName, int entrySeq) {
        if (text == null) {
            text = getFallbackText(language);
        }
        String voiceId = voice.getId();
        TTSEngineType engineType = voice.getEngineType();

        TTSClient ttsClient;
        String modelId;
        String finalText;

        String beforeHr = text.contains("---") ? text.substring(0, text.indexOf("---")) : text;
        String trimmed = beforeHr.replaceAll("(?m)^#+\\s.*$", "").replaceAll("\\[.*?]", "").replace("*", "").replaceAll("\\n+", ". ").replaceAll("\\.{2,}", ".").trim();
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
            finalText = text;
            LOGGER.infof("Using Fish Audio TTS for scene '%s'", sceneTitle);
        } else {
            finalText = text;
            ttsClient = elevenLabsClient;
            modelId = config.getElevenLabsModelId();
            LOGGER.infof("Using ElevenLabs TTS for scene '%s' with model: %s", sceneTitle, modelId);
        }

        String finalText1 = text;
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
                                Map.of(
                                        "seq", entrySeq,
                                        "engineType", engineType.toString(),
                                        "sceneTitle", sceneTitle,
                                        "audioSize", audioBytes.length,
                                        "textLength", finalText1.length(),
                                        "text_to_tts", finalText),
                                traceId);

                        return audioFilePath.toString();
                    } catch (IOException e) {
                        LOGGER.error("Failed to save TTS audio for scene '{}'", sceneTitle, e);
                        metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_tts_audio_save_failed",
                                Map.of("error", e.getMessage(), "sceneTitle", sceneTitle, "engineType", engineType.toString()), traceId);
                        throw new RuntimeException("Failed to save TTS audio", e);
                    }
                })
                .onFailure().invoke(e -> {
                    LOGGER.errorf("%s: voice=%s engine=%s %s", sceneTitle, voiceId, engineType, e.getMessage(), e);
                    metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_tts_audio_generation_failed",
                            Map.of("error", e.getMessage(), "sceneTitle", sceneTitle, "engineType", engineType.toString(), "voiceId", voiceId), traceId);
                });
    }

    private Uni<String> generateDraftText(DjPrompt prompt, SoundFragment song, String sharerName, AiAgent agent, ILiveStream stream) {
        return draftFactory.createDraft(
                song,
                agent,
                stream,
                prompt.getDraftId(),
                stream.getUserVariables(),
                sharerName
        ).map(result -> {
            LOGGER.infof("Draft content received: %s", result.text());
            return result.text();
        });
    }

    private Uni<String> generateSpokenText(DjPrompt prompt, String draftContent, AiAgent agent, LanguageTag language, UUID traceId, String brandName, int entrySeq) {
        if (draftContent.contains("\"error\":") || draftContent.contains("Search failed")) {
            LOGGER.errorf("Draft content contains error, skipping generation: %s", draftContent);
            metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_spoken_text_generation_failed",
                    Map.of("reason", "draft_content_error", "promptId", prompt.getId().toString(), "draft", draftContent), traceId);
            return Uni.createFrom().item((String) null);
        }

        String fullPrompt = String.format(    // regular flow
                "%s\n\nDraft input:\n%s",
                prompt.getPrompt(),
                draftContent
        );

        long maxTokens = 2048L;
        LlmType llmType = agent != null && agent.getLlmType() != null ? agent.getLlmType() : LlmType.CLAUDE;
        LlmTextClient llmTextClient = llmType == LlmType.GROQ ? groqTextClient : anthropicTextClient;
        String apiKey = llmType == LlmType.GROQ ? config.getGroqApiKey().orElse("") : config.getAnthropicApiKey();
        return llmTextClient.createTextMessage(
                        apiKey,
                        llmType == LlmType.GROQ ? config.getIntroTtsGroqModel() : config.getIntroTtsAnthropicModel(),
                        maxTokens,
                        getSystemPrompt(agent, language),
                        fullPrompt)
                .map(response -> {
                    LOGGER.infof("Claude response received - Input tokens: %s, Output tokens: %s",
                            response.inputTokens(), response.outputTokens());

                    String rawGenerated = response.text();
                    String text = stripEmoji(rawGenerated);
                    if (response.outputTokens() >= maxTokens * 0.95) {
                        LOGGER.warnf("Content generation used %s tokens (%s%% of max %s). Response may be truncated.",
                                response.outputTokens(),
                                Math.round((response.outputTokens() / (double) maxTokens) * 100),
                                maxTokens);
                    }

                    if (text.contains("technical difficulty")
                            || text.contains("technical error")
                            || text.contains("technical issue")) {
                        metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_spoken_text_generation_failed",
                                Map.of("reason", "technical_difficulty_detected", "promptId", prompt.getId().toString()), traceId);
                        return null;
                    }

                    LOGGER.infof("Generated text (%s tokens): %s", response.outputTokens(), text);
                    metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.FLOW, "intro_spoken_text_generated",
                            Map.of("seq", entrySeq, "inputTokens", response.inputTokens(), "outputTokens", response.outputTokens(),
                                    "promptId", prompt.getId().toString(), "promptTitle", prompt.getTitle(), "promptText", prompt.getPrompt(), "draft", draftContent, "spokenText", rawGenerated,
                                    "llmProvider", llmType, "djName", agent != null ? agent.getName() : "unknown"), traceId);
                    return text;
                })
                .onFailure().recoverWithItem(e -> {
                    LOGGER.errorf("Anthropic API call failed - Type: %s, Message: %s", e.getClass().getSimpleName(), e.getMessage(), e);
                    metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_spoken_text_generation_failed",
                            Map.of("error", e.getMessage(), "errorType", e.getClass().getSimpleName(), "promptId", prompt.getId().toString()), traceId);
                    return getFallbackText(language);
                });
    }

    private Uni<String> generateSpokenTextFromAction(String renderedInstruction, CustomAction action, Map<String, Object> ctx, AiAgent agent, LanguageTag language, UUID traceId, String brandName, int entrySeq) {
        long maxTokens = 2048L;
        String provider = config.getIntroTtsLlmProvider();
        String model = "groq".equals(provider) ? config.getIntroTtsGroqModel() : config.getIntroTtsAnthropicModel();
        String apiKey = "groq".equals(provider) ? config.getGroqApiKey().orElse("") : config.getAnthropicApiKey();
        LlmTextClient llmTextClient = selectLlmClient(provider);
        return llmTextClient.createTextMessage(
                        apiKey,
                        model,
                        maxTokens,
                        getActionSystemPrompt(agent, language),
                        renderedInstruction)
                .map(response -> {
                    LOGGER.infof("Claude response received - Input tokens: %s, Output tokens: %s",
                            response.inputTokens(), response.outputTokens());

                    String rawGenerated = response.text();
                    String text = stripEmoji(rawGenerated);
                    if (response.outputTokens() >= maxTokens * 0.95) {
                        LOGGER.warnf("Content generation used %s tokens (%s%% of max %s). Response may be truncated.",
                                response.outputTokens(),
                                Math.round((response.outputTokens() / (double) maxTokens) * 100),
                                maxTokens);
                    }

                    if (text.contains("technical difficulty")
                            || text.contains("technical error")
                            || text.contains("technical issue")) {
                        metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_spoken_text_generation_failed",
                                Map.of("reason", "technical_difficulty_detected", "actionName", action.getName()), traceId);
                        return null;
                    }

                    LOGGER.infof("Generated text (%s tokens): %s", response.outputTokens(), text);
                    metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.FLOW, "intro_spoken_text_generated",
                            Map.of("seq", entrySeq, "inputTokens", response.inputTokens(), "outputTokens", response.outputTokens(),
                                    "actionName", action.getName(), "instruction", action.getInstruction(), "variables", ctx,
                                    "spokenText", rawGenerated, "llmProvider", provider, "llmModel", model,
                                    "djName", agent != null ? agent.getName() : "unknown"), traceId);
                    return text;
                })
                .onFailure().recoverWithItem(e -> {
                    LOGGER.errorf("LLM call failed for action '%s' - Type: %s, Message: %s", action.getName(), e.getClass().getSimpleName(), e.getMessage(), e);
                    metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW, "intro_spoken_text_generation_failed",
                            Map.of("error", e.getMessage(), "errorType", e.getClass().getSimpleName(), "actionName", action.getName()), traceId);
                    return getFallbackText(language);
                });
    }

    public Uni<JsonObject> debugPrompt(UUID promptId, SoundFragment song, String sharerName, AiAgent agent, LanguageTag language, ILiveStream stream, LlmType overrideLlmType) {
        LanguageTag resolvedLanguage = language != null ? language : AiHelperUtils.selectLanguageByWeight(agent);
        LlmType llmType = overrideLlmType != null ? overrideLlmType
                : (agent != null && agent.getLlmType() != null ? agent.getLlmType() : LlmType.CLAUDE);
        LlmTextClient llmTextClient = llmType == LlmType.GROQ ? groqTextClient : anthropicTextClient;
        String model = llmType == LlmType.GROQ ? config.getIntroTtsGroqModel() : config.getIntroTtsAnthropicModel();
        String debugApiKey = llmType == LlmType.GROQ ? config.getGroqApiKey().orElse("") : config.getAnthropicApiKey();
        return promptService.getById(promptId, SuperUser.build())
                .chain(prompt -> generateDraftText(prompt, song, sharerName, agent, stream)
                        .chain(draft -> {
                            String fullPrompt = String.format("%s\n\nDraft input:\n%s", prompt.getPrompt(), draft);
                            return llmTextClient.createTextMessage(debugApiKey, model, 2048L, getActionSystemPrompt(agent, resolvedLanguage), fullPrompt)
                                    .map(response -> new JsonObject()
                                            .put("language", resolvedLanguage.tag())
                                            .put("draft", draft)
                                            .put("spokenText", stripEmoji(response.text()))
                                            .put("inputTokens", response.inputTokens())
                                            .put("outputTokens", response.outputTokens()));
                        }));
    }

    public Uni<JsonObject> debugInstruction(String instruction, Map<String, Object> contextVars, AiAgent agent, LanguageTag language) {
        String rendered = renderHandlebars(instruction, contextVars);
        String provider = config.getIntroTtsLlmProvider();
        String model = "groq".equals(provider) ? config.getIntroTtsGroqModel() : config.getIntroTtsAnthropicModel();
        String apiKey = "groq".equals(provider) ? config.getGroqApiKey().orElse("") : config.getAnthropicApiKey();
        LlmTextClient llmTextClient = selectLlmClient(provider);
        return llmTextClient.createTextMessage(apiKey, model, 2048L, getActionSystemPrompt(agent, language), rendered)
                .map(response -> new JsonObject()
                        .put("rendered", rendered)
                        .put("llmResponse", stripEmoji(response.text()))
                        .put("inputTokens", response.inputTokens())
                        .put("outputTokens", response.outputTokens()));
    }

    private Uni<Void> maybeSendDebugEmail(String slugName, String actionName, String instruction, Map<String, Object> variables, String result) {
        return brandService.getBySlugName(slugName)
                .chain(brand -> {
                    if (brand == null) {
                        LOGGER.warnf("maybeSendDebugEmail: brand not found for slug '%s'", slugName);
                        return Uni.createFrom().voidItem();
                    }
                    if (brand.getOwner() == null) {
                        LOGGER.warnf("maybeSendDebugEmail: owner is null for brand '%s'", slugName);
                        return Uni.createFrom().voidItem();
                    }
                    LOGGER.infof("maybeSendDebugEmail: brand='%s' owner email='%s' actionDebugEnabled=%s",
                            slugName, brand.getOwner().getEmail(), brand.getOwner().isActionDebugEnabled());
                    if (brand.getOwner().isActionDebugEnabled()
                            && brand.getOwner().getEmail() != null && !brand.getOwner().getEmail().isBlank()) {
                        return mailService.sendActionDebugEmail(brand.getOwner().getEmail(), actionName, instruction, variables, result);
                    }
                    return Uni.createFrom().voidItem();
                });
    }



    private static String renderHandlebars(String template, Map<String, Object> context) {
        if (template == null) return "";
        try {
            Template compiled = HANDLEBARS.compileInline(template);

            return compiled.apply(context);
        } catch (Exception e) {
            LOGGER.warnf("Handlebars rendering failed, falling back to raw template: %s", e.getMessage());
            return template;
        }
    }

    private LlmTextClient selectLlmClient(String provider) {
        return "groq".equals(provider) ? groqTextClient : anthropicTextClient;
    }

    private String getSystemPrompt(AiAgent agent, LanguageTag language) {
        String langInstruction = (language != null && language.tag() != null)
                ? "CRITICAL: You MUST respond exclusively in the language with BCP-47 tag '" + language.tag() + "' — never switch to any other language regardless of input."
                : "";
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("langInstruction", langInstruction);
        ctx.put("manner", agent != null ? agent.getManner() : null);
        return renderHandlebars(introSystemPromptTemplate, ctx);
    }

    private String getActionSystemPrompt(AiAgent agent, LanguageTag language) {
        String langInstruction = (language != null && language.tag() != null)
                ? "CRITICAL: You MUST respond exclusively in the language with BCP-47 tag '" + language.tag() + "' — never switch to any other language regardless of input."
                : "";
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("langInstruction", langInstruction);
        ctx.put("manner", agent != null ? agent.getManner() : null);
        return renderHandlebars(introActionSystemPromptTemplate, ctx);
    }

    private Uni<IntroAudioResult> calculateDuration(String filePath, LanguageTag languageTag, boolean fallBacked, float gain, TTSEngineType engineType) {
        return Uni.createFrom().item(() -> {
            try {
                FFmpegProbeResult probeResult =
                        ffmpegProvider.getFFprobe().probe(filePath);
                double durationSeconds = probeResult.getFormat().duration;
                int roundedDuration = (int) Math.ceil(durationSeconds);
                LOGGER.infof("Intro audio duration: %s seconds (file: %s)", roundedDuration, filePath);
                return new IntroAudioResult(filePath, roundedDuration, languageTag, fallBacked, gain, engineType);
            } catch (Exception e) {
                LOGGER.warnf("Failed to probe intro audio duration for %s, using default 10s", filePath, e);
                return new IntroAudioResult(filePath, 10, languageTag, fallBacked, gain, engineType);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private String getFallbackText(LanguageTag language) {
        String key = language != null && language.tag() != null ? language.tag().split("-")[0].toLowerCase() : "en";
        JsonArray variants = ttsFallbacks.getJsonArray(key);
        if (variants == null || variants.isEmpty()) {
            variants = ttsFallbacks.getJsonArray("en");
        }
        if (variants == null || variants.isEmpty()) {
            return "Stay tuned for more great music!";
        }
        return variants.getString(RANDOM.nextInt(variants.size()));
    }

    private static String stripEmoji(String input) {
        return input.codePoints()
                .filter(cp -> Character.getType(cp) != Character.OTHER_SYMBOL
                        && !(cp >= 0x1F000 && cp <= 0x1FFFF))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().replace("*", "").replaceAll(" {2,}", " ").trim();
    }
}