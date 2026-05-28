package com.semantyca.jesoos.service.chat.ad;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.util.WebHelper;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.repository.UserAdRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.model.UserAd;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

@ApplicationScoped
public class AdGraph {

    private static final Logger LOGGER = Logger.getLogger(AdGraph.class);

    private static final List<String> REQUIRED_VARS = List.of("title", "description", "contacts");
    private static final Map<String, String> VAR_DESCRIPTIONS = Map.of(
            "title", "the title or name of your advertisement",
            "description", "the ad text or message you want to broadcast",
            "contacts", "contact information (phone, website, email, etc.)"
    );

    @Inject
    AdSessionManager sessionManager;

    @Inject
    UserAdRepository userAdRepository;

    @Inject
    BrandPool brandPool;

    @Inject
    AiAgentService aiAgentService;

    @Inject
    IntroTtsGenerator introTtsGenerator;

    @Inject
    SoundFragmentService soundFragmentService;

    @Inject
    FFmpegProvider ffmpegProvider;

    @Inject
    JesoosConfig config;

    private CompiledGraph<AdState> compiledGraph;
    private AnthropicClient anthropicClient;

    @PostConstruct
    void init() {
        try {
            anthropicClient = AnthropicOkHttpClient.builder()
                    .apiKey(config.getAnthropicApiKey())
                    .build();

            compiledGraph = new StateGraph<>(AdState::new)
                    .addNode("collectTurn",    this::collectTurnNode)
                    .addNode("checkMissing",   this::checkMissingNode)
                    .addNode("askQuestion",    this::askQuestionNode)
                    .addNode("saveAndGenerate", this::saveAndGenerateNode)
                    .addEdge(START, "collectTurn")
                    .addEdge("collectTurn", "checkMissing")
                    .addConditionalEdges("checkMissing",
                            state -> CompletableFuture.completedFuture(
                                    "save".equals(state.action()) ? "saveAndGenerate" : "askQuestion"),
                            Map.of("askQuestion", "askQuestion", "saveAndGenerate", "saveAndGenerate"))
                    .addEdge("askQuestion", END)
                    .addEdge("saveAndGenerate", END)
                    .compile();

            LOGGER.info("[AdGraph] compiled successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile AdGraph", e);
        }
    }

    private CompletableFuture<Map<String, Object>> collectTurnNode(AdState state) {
        String pending = state.pendingVar();
        if (pending == null || pending.isBlank() || state.userMessage().isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Map<String, String> updated = new HashMap<>(state.collectedVars());
        updated.put(pending, state.userMessage().trim());
        LOGGER.infof("[AdGraph] collected var=%s", pending);
        return CompletableFuture.completedFuture(Map.of(AdState.COLLECTED_VARS, updated));
    }

    private CompletableFuture<Map<String, Object>> checkMissingNode(AdState state) {
        String nextMissing = REQUIRED_VARS.stream()
                .filter(v -> !state.collectedVars().containsKey(v) || state.collectedVars().get(v).isBlank())
                .findFirst()
                .orElse(null);

        if (nextMissing == null) {
            LOGGER.info("[AdGraph] all vars collected → saveAndGenerate");
            return CompletableFuture.completedFuture(Map.of(AdState.ACTION, "save"));
        }
        LOGGER.infof("[AdGraph] next missing var=%s", nextMissing);
        return CompletableFuture.completedFuture(Map.of(
                AdState.ACTION, "ask",
                AdState.PENDING_VAR, nextMissing
        ));
    }

    private CompletableFuture<Map<String, Object>> askQuestionNode(AdState state) {
        String varName = state.pendingVar();
        String description = VAR_DESCRIPTIONS.getOrDefault(varName, varName);

        return Uni.createFrom().item(() -> {
            String collected = state.collectedVars().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("none yet");

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5_20251001)
                    .maxTokens(80)
                    .system("You are a friendly radio DJ helping a listener create a short advertisement. " +
                            "You are collecting: title, description, and contacts. " +
                            "Already collected: " + collected + ". " +
                            "Ask for: " + description + ". Keep it short and friendly.")
                    .addUserMessage("Ask the question.")
                    .build();

            Message response = anthropicClient.messages().create(params);
            String question = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(b -> b.asText().text())
                    .findFirst()
                    .orElse("What is the " + description + "?");

            LOGGER.infof("[AdGraph] question for var=%s: %s", varName, question);
            return Map.<String, Object>of(AdState.NEXT_QUESTION, question);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> saveAndGenerateNode(AdState state) {
        String adId = state.savedAdId();
        return CompletableFuture.completedFuture(Map.of(
                AdState.SAVED_AD_ID, adId != null ? adId : ""
        ));
    }

    public Uni<AdResult> processUserTurn(String connectionId, String userMessage) {
        AdSessionData session = sessionManager.get(connectionId)
                .orElseThrow(() -> new IllegalStateException("No active Ad session for " + connectionId));

        Map<String, Object> initData = buildStateMap(session, userMessage);

        return Uni.createFrom().item(() -> {
            try {
                return compiledGraph.invoke(initData)
                        .orElseThrow(() -> new RuntimeException("AdGraph returned empty state"));
            } catch (Exception e) {
                throw new RuntimeException("AdGraph execution failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .flatMap(finalState -> {
                    if ("save".equals(finalState.action())) {
                        return saveAdAndGenerateTts(session, finalState)
                                .map(fragmentId -> {
                                    sessionManager.end(connectionId);
                                    return new AdResult(AdResult.Action.AD_CREATED, fragmentId, null);
                                });
                    } else {
                        session.setCollectedVars(finalState.collectedVars());
                        session.setPendingVar(finalState.pendingVar());
                        sessionManager.update(connectionId, session);
                        return Uni.createFrom().item(
                                new AdResult(AdResult.Action.ASK_QUESTION, null, finalState.nextQuestion()));
                    }
                });
    }

    public Uni<String> generateFirstQuestion(AdSessionData session) {
        Map<String, Object> initData = buildStateMap(session, "");

        return Uni.createFrom().item(() -> {
            try {
                return compiledGraph.invoke(initData)
                        .orElseThrow(() -> new RuntimeException("AdGraph returned empty state"));
            } catch (Exception e) {
                throw new RuntimeException("AdGraph execution failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(finalState -> {
                    session.setPendingVar(finalState.pendingVar());
                    return finalState.nextQuestion();
                });
    }

    private Uni<UUID> saveAdAndGenerateTts(AdSessionData session, AdState state) {
        Map<String, String> vars = state.collectedVars();
        String title = vars.getOrDefault("title", "Advertisement");
        String description = vars.getOrDefault("description", "");
        String contacts = vars.getOrDefault("contacts", "");

        IUser user = new AnonymousUser();

        UserAd ad = new UserAd();
        ad.setUserId(session.getUserId());
        ad.setTitle(title);
        ad.setDescription(description);
        ad.setContacts(contacts);

        return userAdRepository.insert(ad, user)
                .flatMap(adId -> {
                    LOGGER.infof("[AdGraph] UserAd saved id=%s, generating TTS", adId);
                    return brandPool.get(session.getBrandSlug())
                            .flatMap(stream -> aiAgentService.getById(stream.getAiAgentId(), com.semantyca.core.model.user.SuperUser.build())
                                    .flatMap(agent -> {
                                        LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);
                                        String adText = title + ". " + description + ". " + contacts;
                                        UUID traceId = UUID.randomUUID();
                                        return introTtsGenerator.generateTtsAudio(
                                                adText,
                                                agent.getTtsSetting().getNewsReporter(),
                                                lang,
                                                title,
                                                traceId,
                                                session.getBrandSlug()
                                        ).flatMap(filePath -> saveSoundFragment(
                                                filePath, title, adId, session.getBrandSlug(), session.getBrandId(), agent
                                        ));
                                    })
                            );
                });
    }

    private Uni<UUID> saveSoundFragment(String ttsFilePath, String title, UUID adId,
                                        String brandSlug, UUID brandId, com.semantyca.mixpla.model.aiagent.AiAgent agent) {
        return Uni.createFrom().item(() -> {
            try {
                Path path = Path.of(ttsFilePath);
                String fileName = path.getFileName().toString();
                Path targetDir = Paths.get(config.getPathUploads(), "chat-upload-controller", "supervisor", "temp");
                Files.createDirectories(targetDir);
                Path targetFile = targetDir.resolve(fileName);
                Files.copy(path, targetFile, StandardCopyOption.REPLACE_EXISTING);

                int durationSeconds = probeDuration(ttsFilePath);

                SoundFragmentDTO dto = new SoundFragmentDTO();
                dto.setType(PlaylistItemType.ADVERTISEMENT);
                String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
                dto.setTitle(title + " " + currentDate);
                dto.setArtist(brandSlug + "_ad_" + adId);
                dto.setGenres(List.of());
                dto.setLabels(List.of());
                dto.setSource(SourceType.TEMPORARY_MIX);
                dto.setExpiresAt(LocalDate.now().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
                dto.setLength(Duration.ofSeconds(durationSeconds));
                dto.setRepresentedInBrands(List.of(brandId));
                dto.setNewlyUploaded(List.of(fileName));
                dto.setSlugName(WebHelper.generateSlug(dto.getArtist(), dto.getTitle()));
                return dto;
            } catch (Exception e) {
                throw new RuntimeException("Failed to prepare SoundFragment for ad", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(dto -> soundFragmentService.upsert("new", dto, com.semantyca.core.model.user.SuperUser.build(), LanguageCode.en))
                .map(saved -> {
                    LOGGER.infof("[AdGraph] SoundFragment saved id=%s type=ADVERTISEMENT", saved.getId());
                    return saved.getId();
                });
    }

    private int probeDuration(String filePath) {
        try {
            FFmpegProbeResult probeResult = ffmpegProvider.getFFprobe().probe(filePath);
            return (int) Math.ceil(probeResult.getFormat().duration);
        } catch (Exception e) {
            LOGGER.warnf("Failed to probe duration for %s, using default 30s", filePath);
            return 30;
        }
    }

    private Map<String, Object> buildStateMap(AdSessionData session, String userMessage) {
        Map<String, Object> state = new HashMap<>();
        state.put(AdState.USER_MESSAGE, userMessage);
        state.put(AdState.PENDING_VAR, session.getPendingVar() != null ? session.getPendingVar() : "");
        state.put(AdState.COLLECTED_VARS, new HashMap<>(session.getCollectedVars()));
        state.put(AdState.REQUIRED_VARS, new ArrayList<>(REQUIRED_VARS));
        return state;
    }
}
