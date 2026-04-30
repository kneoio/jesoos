package com.semantyca.jesoos.service.live.generated;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.util.WebHelper;
import com.semantyca.jesoos.external.ElevenLabsClient;
import com.semantyca.jesoos.external.GCPTTSClient;
import com.semantyca.jesoos.external.LlmTextClient;
import com.semantyca.jesoos.external.ModelslabClient;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.DjPrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.jboss.logging.Logger;

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
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractGeneratedContentService implements IGeneratedContent {
    private static final Logger LOGGER = Logger.getLogger(AbstractGeneratedContentService.class);

    protected final PromptService promptService;
    protected final SoundFragmentService soundFragmentService;
    protected final SoundFragmentRepository soundFragmentRepository;
    protected final ElevenLabsClient elevenLabsClient;
    protected final ModelslabClient modelslabClient;
    protected final GCPTTSClient gcpttsClient;
    protected final IntroTtsGenerator introTtsGenerator;
    protected final JesoosConfig config;
    protected final LlmTextClient llmTextClient;
    protected final DraftFactory draftFactory;
    protected final AiAgentService aiAgentService;
    protected final FFmpegProvider ffmpegProvider;

    protected AbstractGeneratedContentService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            SoundFragmentRepository soundFragmentRepository,
            ElevenLabsClient elevenLabsClient,
            ModelslabClient modelslabClient,
            GCPTTSClient gcpttsClient,
            IntroTtsGenerator introTtsGenerator,
            JesoosConfig config,
            LlmTextClient llmTextClient,
            DraftFactory draftFactory,
            AiAgentService aiAgentService,
            FFmpegProvider ffmpegProvider
    ) {
        this.promptService = promptService;
        this.soundFragmentService = soundFragmentService;
        this.soundFragmentRepository = soundFragmentRepository;
        this.elevenLabsClient = elevenLabsClient;
        this.modelslabClient = modelslabClient;
        this.gcpttsClient = gcpttsClient;
        this.introTtsGenerator = introTtsGenerator;
        this.config = config;
        this.llmTextClient = llmTextClient;
        this.draftFactory = draftFactory;
        this.aiAgentService = aiAgentService;
        this.ffmpegProvider = ffmpegProvider;
    }

    protected abstract String getSystemPrompt();

    public Uni<SoundFragment> generateAudio(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            LanguageTag airLanguage,
            LiveScene liveScene
    ) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        String lookupKey = stream.getSlugName() + "_" + promptId;

        return soundFragmentRepository.findByArtistAndDate(lookupKey, startOfDay, endOfDay)
                .chain(existing -> {
                    if (existing != null) {
                        LOGGER.infof("Reusing existing generated fragment %s for prompt %s brand %s", existing.getId(), promptId, stream.getSlugName());
                        return Uni.createFrom().item(existing);
                    }
                    return generateAndSave(promptId, agent, stream, airLanguage, liveScene);
                });
    }

    private Uni<SoundFragment> generateAndSave(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            LanguageTag airLanguage,
            LiveScene liveScene
    ) {
        AtomicBoolean fallBacked = new AtomicBoolean(false);
        UUID brandId = stream.getMasterBrandId();
        String sceneTitle = liveScene != null ? liveScene.getSceneTitle() : "AI Generated";
        UUID traceId = liveScene != null ? liveScene.getTraceId() : UUID.randomUUID();

        return promptService.getById(promptId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == airLanguage) {
                        return Uni.createFrom().item(masterPrompt);
                    }
                    return promptService.findByLanguage(promptId, airLanguage)
                            .map(p -> {
                                if (p != null) return p;
                                fallBacked.set(true);
                                return masterPrompt;
                            });
                })
                .chain(prompt -> generateText(prompt, agent, stream, airLanguage)
                        .chain(text -> {
                            if (text == null) {
                                return Uni.createFrom().failure(
                                        new RuntimeException("Text generation failed for scene: " + sceneTitle));
                            }
                            return introTtsGenerator.generateTtsAudio(text, agent.getTtsSetting().getNewsReporter(), airLanguage, sceneTitle, traceId, stream.getSlugName())
                                    .chain(filePath -> saveSoundFragment(filePath, prompt, brandId, promptId, stream.getSlugName()));
                        })
                );
    }

    private Uni<SoundFragment> saveSoundFragment(
            String ttsFilePath,
            DjPrompt prompt,
            UUID brandId,
            UUID promptId,
            String brandSlug
    ) {
        return Uni.createFrom().item(() -> {
            try {
                Path path = Path.of(ttsFilePath);
                String fileName = path.getFileName().toString();
                boolean sourceExists = Files.exists(path);
                LOGGER.infof(
                        "saveSoundFragment preparing file copy: source=%s (exists=%s), fileName=%s, brandId=%s, promptId=%s",
                        path.toAbsolutePath(), sourceExists, fileName, brandId, promptId
                );

                Path targetDir = Paths.get(config.getPathUploads(), "chat-upload-controller", "supervisor", "temp");
                Files.createDirectories(targetDir);
                Path targetFile = targetDir.resolve(fileName);
                Files.copy(path, targetFile, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.infof(
                        "saveSoundFragment copied file: target=%s (exists=%s)",
                        targetFile.toAbsolutePath(), Files.exists(targetFile)
                );

                int durationSeconds = probeDuration(ttsFilePath);

                SoundFragmentDTO dto = new SoundFragmentDTO();
                dto.setType(PlaylistItemType.NEWS);
                String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
                dto.setTitle(prompt.getTitle() + " " + currentDate);
                dto.setArtist(brandSlug + "_" + promptId);
                dto.setGenres(List.of());
                dto.setLabels(List.of());
                dto.setSource(SourceType.TEMPORARY_MIX);
                dto.setExpiresAt(LocalDate.now().plusDays(1).atStartOfDay());
                dto.setLength(Duration.ofSeconds(durationSeconds));
                LOGGER.infof("saveSoundFragment DTO ready: brandId=%s, promptId=%s, newlyUploaded=%s", brandId, promptId, fileName);
                if (brandId == null) {
                    throw new IllegalStateException("brandId is null — stream.getMasterBrandId() returned null");
                }
                dto.setRepresentedInBrands(List.of(brandId));
                dto.setNewlyUploaded(List.of(fileName));
                dto.setSlugName(WebHelper.generateSlug(dto.getArtist(), dto.getTitle()));

                return dto;
            } catch (IOException e) {
                throw new RuntimeException("Failed to prepare SoundFragment file", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
        .chain(dto -> soundFragmentService.upsert("new", dto, SuperUser.build(), LanguageCode.en))
        .map(savedDto -> {
            SoundFragment fragment = new SoundFragment();
            fragment.setId(savedDto.getId());
            fragment.setType(savedDto.getType());
            fragment.setTitle(savedDto.getTitle());
            fragment.setArtist(savedDto.getArtist());
            fragment.setLength(savedDto.getLength());
            fragment.setSlugName(savedDto.getSlugName());
            fragment.setSource(savedDto.getSource());
            fragment.setExpiresAt(savedDto.getExpiresAt());
            LOGGER.infof("Generated content saved as SoundFragment %s", fragment.getId());
            return fragment;
        });
    }

    private int probeDuration(String filePath) {
        try {
            FFmpegProbeResult probeResult = ffmpegProvider.getFFprobe().probe(filePath);
            return (int) Math.ceil(probeResult.getFormat().duration);
        } catch (Exception e) {
            LOGGER.warnf("Failed to probe duration for %s, using default 180s", filePath);
            return 180;
        }
    }

    private Uni<String> generateText(DjPrompt prompt, AiAgent agent, IStream stream, LanguageTag airLanguage) {
        return draftFactory.createDraft(null, agent, stream, prompt.getDraftId(), airLanguage, new HashMap<>())
                .chain(draftContent -> Uni.createFrom().item(() -> {
                    if (draftContent.contains("\"error\":") || draftContent.contains("Search failed")) {
                        LOGGER.errorf("Draft content contains error, skipping: %s", draftContent);
                        return null;
                    }

                    String fullPrompt = prompt.getPrompt() + "\n\nDraft input:\n" + draftContent;
                    long maxTokens = 2048L;
                    String provider = config.getGeneratedLlmProvider();
                    String model = "groq".equals(provider) ? config.getGeneratedGroqModel() : config.getGeneratedAnthropicModel();

                    try {
                        var response = llmTextClient.createTextMessage(model, maxTokens, getSystemPrompt(), fullPrompt)
                                .await().indefinitely();
                        String text = response.text();

                        if (text.contains("technical difficulty")
                                || text.contains("technical error")
                                || text.contains("technical issue")) {
                            LOGGER.warnf("Generated text signals technical error, skipping");
                            return null;
                        }

                        LOGGER.infof("Generated text (%d tokens): %s", response.outputTokens(), text);
                        return text;
                    } catch (Exception e) {
                        LOGGER.errorf("LLM API call failed: %s", e.getMessage(), e);
                        throw e;
                    }
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    }
}
