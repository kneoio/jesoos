package com.semantyca.djinn.service.live.generated;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.agent.GCPTTSClient;
import com.semantyca.djinn.agent.ModelslabClient;
import com.semantyca.djinn.agent.TextToSpeechClient;
import com.semantyca.djinn.config.DjinnConfig;
import com.semantyca.djinn.dto.SoundFragmentDTO;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.djinn.service.AiAgentService;
import com.semantyca.djinn.service.PromptService;
import com.semantyca.djinn.service.live.scripting.DraftFactory;
import com.semantyca.djinn.service.manipulation.FFmpegProvider;
import com.semantyca.djinn.service.manipulation.mixing.AudioConcatenator;
import com.semantyca.djinn.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.Voice;
import com.semantyca.mixpla.model.cnst.GeneratedContentStatus;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import com.semantyca.mixpla.service.exceptions.AudioMergeException;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public abstract class AbstractGeneratedContentService implements IGeneratedContent {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractGeneratedContentService.class);

    protected final PromptService promptService;
    protected final SoundFragmentService soundFragmentService;
    protected final ModelslabClient modelslabClient;
    protected final GCPTTSClient gcpttsClient;
    protected final DjinnConfig config;
    protected final DraftFactory draftFactory;
    protected final AiAgentService aiAgentService;
    protected final SoundFragmentRepository soundFragmentRepository;
    protected final FFmpegProvider ffmpegProvider;
    protected final AudioConcatenator audioConcatenator;

    protected AbstractGeneratedContentService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            ModelslabClient modelslabClient,
            GCPTTSClient gcpttsClient,
            DjinnConfig config,
            DraftFactory draftFactory,
            AiAgentService aiAgentService,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider ffmpegProvider,
            AudioConcatenator audioConcatenator
    ) {
        this.promptService = promptService;
        this.soundFragmentService = soundFragmentService;
        this.modelslabClient = modelslabClient;
        this.gcpttsClient = gcpttsClient;
        this.config = config;
        this.draftFactory = draftFactory;
        this.aiAgentService = aiAgentService;
        this.soundFragmentRepository = soundFragmentRepository;
        this.ffmpegProvider = ffmpegProvider;
        this.audioConcatenator = audioConcatenator;
    }

    protected abstract String getIntroJingleResource();
    protected abstract String getBackgroundMusicResource();
    protected abstract PlaylistItemType getContentType();
    protected abstract Voice getVoice(AiAgent agent);
    protected abstract String getSystemPrompt();

    public Uni<SoundFragment> findOrGenerateFragment(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            LiveScene activeEntry,
            LanguageTag airLanguage
    ) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);

        return soundFragmentService.findByArtistAndDate(promptId.toString(), startOfDay, endOfDay)
                .flatMap(existingFragment -> {
                    if (existingFragment != null) {
                        if (existingFragment.getExpiresAt() != null &&
                                existingFragment.getExpiresAt().isBefore(LocalDateTime.now())) {
                            LOGGER.info("Fragment {} expired at {}, regenerating", existingFragment.getId(), existingFragment.getExpiresAt());
                            return generateFragment(promptId, agent, stream, activeEntry, airLanguage);
                        }
                        LOGGER.info("Reusing existing fragment {} for prompt {}", existingFragment.getId(), promptId);
                        if (activeEntry != null) {
                            LocalDateTime scheduledTime = activeEntry.getScheduledStartTime();
                            PendingSongEntry entry = new PendingSongEntry(existingFragment, scheduledTime);
                            activeEntry.addSong(entry);
                        }
                        return Uni.createFrom().item(existingFragment);
                    }
                    return generateFragment(promptId, agent, stream, activeEntry, airLanguage);
                });
    }

    public Uni<SoundFragment> generateFragment(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            LiveScene activeEntry,
            LanguageTag airLanguage
    ) {
        UUID brandId = stream.getMasterBrandId();
        String sceneTitle = activeEntry != null ? activeEntry.getSceneTitle() : "AI Generated";
        LocalDateTime scheduledTime = activeEntry != null ? activeEntry.getScheduledStartTime() : LocalDateTime.now();
        
        return promptService.getById(promptId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == airLanguage) {
                        return Uni.createFrom().item(masterPrompt);
                    }
                    return promptService
                            .findByMasterAndLanguage(promptId, airLanguage, false)
                            .map(p -> p != null ? p : masterPrompt);
                })
                .chain(prompt -> generateText(prompt, agent, stream, airLanguage)
                        .chain(text -> {
                            if (text == null) {
                                return Uni.createFrom().failure(new RuntimeException("Generated content contains technical difficulty/error - skipping generation"));
                            }
                            return generateTtsAndSave(text, prompt, agent, brandId, sceneTitle, scheduledTime, activeEntry);
                        })
                );
    }

    private Uni<String> generateText(Prompt prompt, AiAgent agent, IStream stream, LanguageTag broadcastingLanguage) {
        return draftFactory.createDraft(
                null,
                agent,
                stream,
                prompt.getDraftId(),
                LanguageTag.EN_US,
                new HashMap<>()
        ).chain(draftContent -> Uni.createFrom().item(() -> {
            LOGGER.info("Draft content received: {}", draftContent);

            if (draftContent.contains("\"error\":") || draftContent.contains("Search failed")) {
                LOGGER.error("Draft content contains error, skipping generation: {}", draftContent);
                return null;
            }

            String fullPrompt = String.format(
                    "%s\n\nDraft input:\n%s",
                    prompt.getPrompt(),
                    draftContent
            );

            LOGGER.info("Sending prompt to Claude (length: {} chars)", fullPrompt.length());

            long maxTokens = 2048L;
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5_20251001)
                    .maxTokens(maxTokens)
                    .system(getSystemPrompt())
                    .addUserMessage(fullPrompt)
                    .build();

            try {
                AnthropicClient anthropicClient = AnthropicOkHttpClient.builder()
                        .apiKey(config.getAnthropicApiKey())
                        .timeout(Duration.ofSeconds(60))
                        .build();
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
                    return null;
                } else {
                    LOGGER.info("Generated text ({} tokens): {}", response.usage().outputTokens(), text);
                    return text;
                }
            } catch (Exception e) {
                LOGGER.error("Anthropic API call failed - Type: {}, Message: {}", e.getClass().getSimpleName(), e.getMessage(), e);
                throw e;
            }
        }));
    }

    private Uni<SoundFragment> generateTtsAndSave(
            String text,
            Prompt prompt,
            AiAgent agent,
            UUID brandId,
            String sceneTitle,
            LocalDateTime scheduledTime,
            LiveScene activeEntry
    ) {
        String uploadId = UUID.randomUUID().toString();

        Voice voice = getVoice(agent);
        if (voice == null) {
            throw new RuntimeException("Voice not configured for " + getContentType());
        }

        return Uni.createFrom().item(voice).chain(v -> {
            LOGGER.info("Starting TTS generation for scene '{}' using voice: {} (engine: {})",
                    sceneTitle, voice.getId(), voice.getEngineType());

            TextToSpeechClient ttsClient;
            String modelId;

            if (voice.getEngineType() == TTSEngineType.GOOGLE) {
                ttsClient = gcpttsClient;
                modelId = null;
                LOGGER.info("Using GCP TTS client");

            } else {
                ttsClient = modelslabClient;
                modelId = null;
                LOGGER.info("Using Modelslab TTS client");
            }

            LOGGER.info("Calling TTS API with text length: {} characters", text.length());
            return ttsClient.textToSpeech(text, voice.getId(), modelId, agent.getPreferredLang().getFirst().getLanguageTag())
                    .chain(audioBytes -> {
                        LOGGER.info("TTS generation successful! Received {} bytes of audio data", audioBytes.length);
                        try {
                            Path uploadsDir = Paths.get(config.getPathUploads(), "generated-content-service", "supervisor", "temp");
                            Files.createDirectories(uploadsDir);

                            String fileName = "generated_content_" + uploadId + ".mp3";
                            Path ttsFilePath = uploadsDir.resolve(fileName);
                            Files.write(ttsFilePath, audioBytes);

                            LOGGER.info("Generated content TTS saved: {}", ttsFilePath);

                            AudioMixingHandler mixingHandler = new AudioMixingHandler(
                                    config,
                                    soundFragmentRepository,
                                    soundFragmentService,
                                    audioConcatenator,
                                    aiAgentService,
                                    ffmpegProvider
                            );

                            String mixedFileName = "mixed_content_" + uploadId + ".wav";
                            Path mixedFilePath = uploadsDir.resolve(mixedFileName);

                            return mixingHandler.mixContentWithBackgroundAndIntros(
                                    ttsFilePath.toString(),
                                    mixedFilePath.toString(),
                                    0.40,
                                    getIntroJingleResource(),
                                    getBackgroundMusicResource()
                            ).chain(mixedPath -> {
                                LOGGER.info("Content mixed with background and jingles: {}", mixedPath);
                                return createAndSaveSoundFragment(
                                        Path.of(mixedPath),
                                        prompt,
                                        brandId,
                                        scheduledTime,
                                        activeEntry,
                                        text
                                );
                            });
                        } catch (IOException | AudioMergeException e) {
                            LOGGER.error("Failed to save or mix TTS audio for scene '{}'", sceneTitle, e);
                            return Uni.createFrom().failure(e);
                        }
                    })
                    .onFailure().recoverWithUni(error -> {
                        LOGGER.error("TTS generation failed for scene '{}' - Error: {}",
                                sceneTitle, error.getMessage(), error);
                        if (activeEntry != null) {
                            activeEntry.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
                        }
                        return Uni.createFrom().failure(error);
                    });
        });
    }

    private Uni<SoundFragment> createAndSaveSoundFragment(
            Path audioFilePath,
            Prompt prompt,
            UUID brandId,
            LocalDateTime scheduledTime,
            LiveScene activeEntry,
            String text
    ) {
        try {
            Path targetDir = Paths.get(config.getPathUploads(), "sound-fragments-controller", "supervisor", "temp");
            Files.createDirectories(targetDir);

            Path targetPath = targetDir.resolve(audioFilePath.getFileName());
            Files.copy(audioFilePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            SoundFragmentDTO dto = new SoundFragmentDTO();
            dto.setType(getContentType());
            String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            dto.setTitle(prompt.getTitle() + " " + currentDate);
            dto.setArtist(prompt.getId().toString());
            dto.setGenres(List.of());
            dto.setLabels(List.of());
            dto.setSource(SourceType.TEMPORARY_MIX);
            dto.setExpiresAt(LocalDate.now().plusDays(1).atStartOfDay());
            dto.setLength(Duration.ofSeconds(30));
            String truncatedText = text.length() > 200 ? text.substring(0, 200) + "..." : text;
            dto.setDescription("AI generated content " + currentDate + "\n\nContent: " + truncatedText);
            dto.setRepresentedInBrands(List.of(brandId));
            dto.setNewlyUploaded(List.of(audioFilePath.getFileName().toString()));

            String slugName = WebHelper.generateSlug(dto.getArtist(), dto.getTitle());
            dto.setSlugName(slugName);

            return soundFragmentService.upsert("new", dto, SuperUser.build(), LanguageCode.en)
                    .map(savedDto -> {
                        SoundFragment fragment = new SoundFragment();
                        fragment.setId(savedDto.getId());
                        fragment.setType(savedDto.getType());
                        fragment.setTitle(savedDto.getTitle());
                        fragment.setArtist(savedDto.getArtist());
                        fragment.setGenres(savedDto.getGenres());
                        fragment.setLabels(savedDto.getLabels());
                        fragment.setSource(savedDto.getSource());
                        fragment.setExpiresAt(savedDto.getExpiresAt());
                        fragment.setLength(savedDto.getLength());
                        fragment.setDescription(savedDto.getDescription());
                        fragment.setSlugName(savedDto.getSlugName());

                        if (activeEntry != null) {
                            PendingSongEntry entry = new PendingSongEntry(fragment, scheduledTime);
                            activeEntry.addSong(entry);
                            activeEntry.setGeneratedContentStatus(GeneratedContentStatus.GENERATED);
                        }

                        LOGGER.info("Generated fragment saved: {}", fragment.getId());
                        return fragment;
                    });
        } catch (IOException | DocumentModificationAccessException e) {
            LOGGER.error("Failed to copy audio file to target directory", e);
            return Uni.createFrom().failure(e);
        }
    }
}

