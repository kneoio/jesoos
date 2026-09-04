package com.semantyca.jesoos.service.chat;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.external.KeycloakAuthService;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.*;
import com.semantyca.jesoos.service.chat.ad.AdGraph;
import com.semantyca.jesoos.service.chat.ad.AdSessionManager;
import com.semantyca.jesoos.service.chat.llm.*;
import com.semantyca.jesoos.service.chat.tools.*;
import com.semantyca.jesoos.service.chat.tools.ad.CreateAdTool;
import com.semantyca.jesoos.service.chat.tools.ad.CreateAdToolHandler;
import com.semantyca.jesoos.service.chat.tools.auth.*;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.OneTimeStreamPool;
import com.semantyca.jesoos.service.live.SongEmitter;
import com.semantyca.jesoos.service.soundfragment.SharedSoundFragmentService;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.ws.PublicChatController;
import com.semantyca.core.service.mail.MailService;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

@ApplicationScoped
public class ChatAgent {

    private static final Logger LOGGER = Logger.getLogger(ChatAgent.class);
    private static final int MAX_TOOL_ITERATIONS = 8;

    @Inject JesoosConfig config;
    @Inject ChatRepository chatRepository;
    @Inject AiHelperService aiHelperService;
    @Inject ListenerService listenerService;
    @Inject BrandService brandService;
    @Inject UserService userService;
    @Inject PublicChatSessionManager sessionManager;
    @Inject KeycloakAuthService keycloakAuthService;
    @Inject PublicChatController controller;
    @Inject MetricPublisher metricPublisher;
    @Inject MailService mailService;
    @Inject AiAgentService aiAgentService;
    @Inject BrandPool brandPool;
    @Inject OneTimeStreamPool oneTimeStreamPool;
    @Inject SongEmitter songEmitter;
    @Inject SoundFragmentService soundFragmentService;
    @Inject SharedSoundFragmentService sharedSoundFragmentService;
    @Inject IntroTtsGenerator introTtsGenerator;
    @Inject InternalRestCall internalRestCall;
    @Inject com.semantyca.jesoos.outbound.SpectraApiClient spectraClient;
    @Inject AdSessionManager adSessionManager;
    @Inject AdGraph adGraph;
    @Inject EventService eventService;
    @Inject ListenerLabelCache listenerLabelCache;
    @Inject ChatService publicChatService;
    @Inject ChatAuthService chatAuthService;
    @Inject SunoImportService sunoImportService;
    @Inject com.semantyca.jesoos.service.agenda.AgendaViewService agendaViewService;
    @Inject BrandLlmProviderResolver llmProviderResolver;

    private CompiledGraph<ChatState> compiledGraph;

    @PostConstruct
    void init() {
        try {
            compiledGraph = new StateGraph<>(new ChatStateSerializer())
                    .addNode("loadContext", this::loadContextNode)
                    .addNode("llm", this::llmNode)
                    .addNode("tool", this::toolNode)
                    .addEdge(START, "loadContext")
                    .addEdge("loadContext", "llm")
                    .addConditionalEdges("llm",
                            state -> CompletableFuture.completedFuture(
                                    state.toolCall() != null && state.iteration() < MAX_TOOL_ITERATIONS ? "tool" : END),
                            Map.of("tool", "tool", END, END))
                    .addEdge("tool", "llm")
                    .compile();

            LOGGER.info("[ChatAgent] compiled successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile ChatAgent", e);
        }
    }

    private CompletableFuture<Map<String, Object>> loadContextNode(ChatState state) {
        String brandName = state.brandName();
        long userId = state.userId();

        Uni<String> queueUni = internalRestCall.getQueueFromAivox(brandName)
                .map(queue -> {
                    if (queue == null || queue.isEmpty()) return "";
                    String nowPlaying = "";
                    StringBuilder upcoming = new StringBuilder();
                    int upcomingCount = 0;
                    for (int i = 0; i < queue.size(); i++) {
                        JsonObject track = queue.getJsonObject(i);
                        if (track == null) continue;
                        String queueType = track.getString("queueType", "");
                        String title = track.getString("title", "?");
                        String artist = track.getString("artist", "");
                        String trackStr = "\"" + title + "\"" + (artist.isBlank() ? "" : " by " + artist);
                        if ("playing".equals(queueType)) {
                            nowPlaying = "[Now playing: " + trackStr + "]";
                        } else if (!"played".equals(queueType) && upcomingCount < 5) {
                            if (upcomingCount > 0) upcoming.append(" → ");
                            upcoming.append(trackStr);
                            upcomingCount++;
                        }
                    }
                    StringBuilder result = new StringBuilder();
                    if (!nowPlaying.isEmpty()) result.append(nowPlaying);
                    if (upcomingCount > 0) {
                        if (!result.isEmpty()) result.append(" ");
                        result.append("[Upcoming: ").append(upcoming).append("]");
                    }
                    return result.toString();
                })
                .onFailure().recoverWithItem(err -> {
                    LOGGER.warnf("[loadContext] queue fetch failed for brand=%s: %s", brandName, err.getMessage());
                    return "";
                });

        Uni<String> listenerUni = (userId == 0)
                ? Uni.createFrom().item("")
                : listenerService.getByUserId(userId)
                        .map(listener -> {
                            if (listener == null) return "";
                            StringBuilder sb = new StringBuilder("[Listener profile:");
                            com.semantyca.core.model.UserData ud = listener.getUserData();
                            if (ud != null && ud.getData() != null) {
                                ud.getData().forEach((k, v) -> sb.append(" ").append(k).append("=").append(v).append(";"));
                            }
                            if (listener.getLocalizedName() != null && !listener.getLocalizedName().isEmpty()) {
                                listener.getLocalizedName().forEach((lang, name) -> sb.append(" localized_name(").append(lang).append(")=").append(name).append(";"));
                            }
                            List<String> resolvedLabels = listenerLabelCache.resolveToIdentifiers(listener.getLabels());
                            if (!resolvedLabels.isEmpty()) {
                                sb.append(" labels=").append(resolvedLabels).append(";");
                            }
                            sb.append("]");
                            return sb.toString();
                        })
                        .onFailure().recoverWithItem(err -> {
                            LOGGER.warnf("[loadContext] listener fetch failed userId=%d: %s", userId, err.getMessage());
                            return "";
                        });

        return Uni.combine().all().unis(queueUni, listenerUni).asTuple()
                .map(t -> {
                    LOGGER.debugf("[loadContext] context built brand=%s userId=%d", brandName, userId);
                    Map<String, Object> result = new HashMap<>();
                    result.put(ChatState.CONTEXT_BLOCK, t.getItem1().trim());  //current playing
                    result.put(ChatState.LISTENER_CONTEXT, t.getItem2().trim());
                    return result;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> llmNode(ChatState state) {
        boolean isAuthenticated = state.userId() != 0;
        String djLanguages = state.djLanguages();
        String brandName = state.brandName();
        List<LlmTool> tools = state.isOts()
                ? getToolsForOts(djLanguages)
                : getToolsForUser(isAuthenticated, djLanguages, state.adEnabled());

        String model = state.iteration() == 0
                ? llmProviderResolver.modelFor(brandName, LlmUseCase.MAIN_CHAT)
                : llmProviderResolver.modelFor(brandName, LlmUseCase.FOLLOW_UP);

        String base = state.systemPrompt()
                .replace("{{isAuthenticated}}", Boolean.toString(isAuthenticated));
        if (!isAuthenticated) {
            int gateIdx = base.indexOf("!! AUTHENTICATED ONLY");
            if (gateIdx >= 0) base = base.substring(0, gateIdx).trim();
        }
        // Keep per-request live/listener context out of the cached block so the static
        // instruction body stays a stable, reusable cache prefix across iterations and turns.
        String stableSystem = base
                .replace("{{liveContext}}", "")
                .replace("{{listenerContext}}", "");
        String volatileSystem = buildVolatileContext(state.contextBlock(), state.listenerContext());

        LlmRequest request = LlmRequest.builder()
                .maxTokens(1024L)
                .systemStable(stableSystem)
                .systemVolatile(volatileSystem)
                .cacheSystem(true)
                .messages(state.history())
                .model(model)
                .tools(tools)
                .build();

        return Uni.createFrom().completionStage(() -> llmProviderResolver.clientFor(brandName).createMessage(request))
                .map(response -> {
                    Map<String, Object> updates = new HashMap<>();
                    if (response.toolCall().isPresent()) {
                        LOGGER.infof("[ChatGraph] llm→tool=%s userId=%d iter=%d", response.toolCall().get().name(), state.userId(), state.iteration());
                        updates.put(ChatState.TOOL_CALL, response.toolCall().get());
                        updates.put(ChatState.BOT_RESPONSE, null);
                    } else {
                        LOGGER.infof("[ChatGraph] llm→text userId=%d iter=%d", state.userId(), state.iteration());
                        updates.put(ChatState.TOOL_CALL, null);
                        updates.put(ChatState.BOT_RESPONSE, response.text());
                    }
                    return updates;
                })
                .onFailure().recoverWithItem(err -> {
                    LOGGER.errorf(err, "[ChatGraph] llmNode failed");
                    return Map.of(ChatState.TOOL_CALL, (Object) null, ChatState.BOT_RESPONSE, "");
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private static String buildVolatileContext(String liveContext, String listenerContext) {
        StringBuilder sb = new StringBuilder();
        if (liveContext != null && !liveContext.isBlank()) sb.append(liveContext.trim()).append("\n");
        if (listenerContext != null && !listenerContext.isBlank()) sb.append(listenerContext.trim()).append("\n");
        if (sb.length() == 0) return "";
        return "----------------------------------------\n"
                + "CURRENT CONTEXT (live, this request)\n"
                + "----------------------------------------\n"
                + sb.toString().strip();
    }

    private static String toolStatusMessage(String toolName) {
        return switch (toolName) {
            case "play_song_with_intro"       -> "Queueing your song...";
            case "start_auth"                 -> "Sending verification code...";
            case "verify_code"                -> "Verifying code...";
            case "upload_song"                -> "Uploading your track...";
            case "assess_track"               -> "Listening to your track...";
            case "import_from_suno"           -> "Fetching your track from Suno...";
            case "search_brand_sound_fragments" -> "Searching catalog...";
            case "get_brand_catalog_summary"  -> "Loading catalog...";
            case "create_ad"                  -> "Setting up your ad...";
            case "listener_data"              -> "Remembering...";
            case "logoff"                     -> "Signing out...";
            default                           -> null;
        };
    }

    private CompletableFuture<Map<String, Object>> toolNode(ChatState state) {
        LlmToolCall toolCall = state.toolCall();
        List<LlmMessage> history = new ArrayList<>(state.history());
        String connectionId = state.connectionId();
        String brandName = state.brandName();
        long userId = state.userId();

        String status = toolStatusMessage(toolCall.name());
        if (status != null) {
            controller.sendToConnection(connectionId,
                    com.semantyca.jesoos.dto.ChatMessageDTO.processing(status, connectionId).build().toJson());
        }

        return executeToolCall(toolCall, state)
                .chain(result -> {
                    history.add(LlmMessage.toolUse(toolCall));
                    history.add(LlmMessage.toolResult(toolCall.id(), result.payload()));

                    Map<String, Object> updates = new HashMap<>();
                    updates.put(ChatState.HISTORY, history);
                    updates.put(ChatState.TOOL_CALL, null);
                    updates.put(ChatState.ITERATION, state.iteration() + 1);

                    // Logoff
                    if (result.clearHistory()) {
                        updates.put(ChatState.USER_ID, 0L);
                    }

                    if (result.wsMessage() != null) {
                        controller.sendToConnection(connectionId, result.wsMessage());
                    }

                    if (result.newUserId() != null && result.newUserId() > 0 && result.newUser() != null) {
                        updates.put(ChatState.USER_ID, result.newUserId());
                        if (result.sessionToken() != null) {
                            updates.put(ChatState.SESSION_TOKEN, result.sessionToken());
                            updates.put(ChatState.SESSION_USER_NAME, result.sessionUserName());
                        }
                        chatRepository.persistConnectionToUser(connectionId, result.newUserId());
                        LOGGER.infof("[ChatAgent] auth upgraded userId=%d connectionId=%s", result.newUserId(), connectionId);
                        return chatAuthService.migrateAnonymousDbRecords(connectionId, result.newUserId())
                                .onFailure().invoke(err -> LOGGER.warnf(err, "[ChatAgent] migration failed conn=%s", connectionId))
                                .onFailure().recoverWithNull()
                                .invoke(() -> controller.upgradeUserSession(connectionId, result.newUser()))
                                .replaceWith(updates);
                    }

                    return Uni.createFrom().item(updates);
                })
                .onFailure().recoverWithItem(err -> {
                    LOGGER.errorf(err, "[ChatGraph] toolNode failed for tool=%s", toolCall.name());
                    history.add(LlmMessage.toolUse(toolCall));
                    history.add(LlmMessage.toolResult(toolCall.id(),
                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
                    return Map.of(ChatState.HISTORY, history, ChatState.TOOL_CALL, (Object) null,
                            ChatState.ITERATION, state.iteration() + 1);
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private Uni<ToolNodeResult> executeToolCall(LlmToolCall toolCall, ChatState state) {
        Map<String, Object> input = toolCall.input();
        String connectionId = state.connectionId();
        String brandName = state.brandName();
        long userId = state.userId();
        String djName = state.djName();

        return switch (toolCall.name()) {
            case "start_auth" -> StartAuthToolHandler.execute(input, keycloakAuthService);
            case "verify_code" -> VerifyCodeToolHandler.execute(input, sessionManager, userService,
                    chatAuthService, controller, brandName, connectionId, metricPublisher);
            case "logoff" -> LogoffToolHandler.execute(input, sessionManager, userService, controller,
                    publicChatService, metricPublisher, brandName, userId, connectionId);
            case "search_brand_sound_fragments" -> state.isOts()
                    ? SearchOtsSoundFragmentsToolHandler.execute(input, brandName, oneTimeStreamPool, aiHelperService)
                    : SearchBrandSoundFragmentsToolHandler.execute(input, aiHelperService);
            case "get_brand_catalog_summary" -> GetBrandCatalogSummaryToolHandler.execute(input, aiHelperService);
            case "listener_data" -> ListenerDataToolHandler.execute(input, listenerService, listenerLabelCache, userId);
            case "find_community_member" -> FindCommunityMemberToolHandler.execute(input, listenerService, brandName, userId);
            case "inform_owner" -> SendEmailToOwnerToolHandler.execute(input, brandService, userService,
                    mailService, userId, brandName);
            case "upload_song" -> UploadSongToolHandler.execute(input, listenerService, userService,
                    soundFragmentService, sharedSoundFragmentService, aiHelperService, brandPool, songEmitter, aiAgentService,
                    listenerLabelCache, brandService, spectraClient, config, brandName, userId);
            case "assess_track" -> AssessTrackToolHandler.execute(input, spectraClient, userService, config, userId);
            case "import_from_suno" -> SunoImportToolHandler.execute(input, sunoImportService,
                    listenerService, userService, listenerLabelCache, userId);
            case "play_song_with_intro" -> state.isOts()
                    ? PlaySongForOtsToolHandler.execute(input, brandName, aiAgentService,
                        oneTimeStreamPool, introTtsGenerator, internalRestCall)
                    : PlaySongWithIntroToolHandler.execute(input, aiAgentService,
                        brandPool, introTtsGenerator, internalRestCall);
            case "create_ad" -> CreateAdToolHandler.execute(input, brandService, adSessionManager, adGraph,
                    userId, brandName, djName, connectionId);
            case "manage_events" -> ManageEventsToolHandler.execute(input, eventService, brandService, brandName);
            case "send_ui_command" -> {
                if ("show_upload_button".equals(input.getOrDefault("command", ""))) {
                    sessionManager.grantUploadPermission(userId);
                }
                yield SendUICommandToolHandler.execute(input, connectionId);
            }
            default -> {
                LOGGER.warnf("[ChatGraph] unknown tool: %s", toolCall.name());
                yield Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "Unknown tool: " + toolCall.name()).encode()));
            }
        };
    }

    public Uni<ChatState> run(Map<String, Object> initData) {
        return Uni.createFrom().item(() -> {
            try {
                return compiledGraph.invoke(initData)
                        .orElseThrow(() -> new RuntimeException("ChatGraph returned empty state"));
            } catch (Exception e) {
                throw new RuntimeException("ChatGraph execution failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // OTS chat runs for anonymous event guests (no auth). The only capabilities are finding a song
    // and queueing it with a spoken shout-out/dedication. Scope (brand vs owner catalog) and routing
    // are resolved inside the OTS handlers from the live stream, not here.
    private List<LlmTool> getToolsForOts(String djLanguages) {
        List<LlmTool> tools = new ArrayList<>();
        tools.add(SearchBrandSoundFragments.toTool());
        tools.add(PlaySongWithIntroTool.toTool(djLanguages));
        return tools;
    }

    private List<LlmTool> getToolsForUser(boolean isAuthenticated, String djLanguages, boolean adEnabled) {
        List<LlmTool> tools = new ArrayList<>();
        tools.add(SendEmailToOwnerTool.toTool());
        if (isAuthenticated) {
            tools.add(SearchBrandSoundFragments.toTool());
            tools.add(GetBrandCatalogSummary.toTool());
            tools.add(ListenerDataTool.toTool());
            tools.add(FindCommunityMemberTool.toTool());
            tools.add(UploadSongTool.toTool());
            tools.add(AssessTrackTool.toTool());
            tools.add(SunoImportTool.toTool());
            tools.add(PlaySongWithIntroTool.toTool(djLanguages));
            if (adEnabled) {
                tools.add(CreateAdTool.toTool());
            }
            tools.add(ManageEventsTool.toTool());
            tools.add(SendUICommandTool.toTool());
            tools.add(LogoffTool.toTool());
        } else {
            tools.add(StartAuthTool.toTool());
            tools.add(VerifyCode.toTool());
        }
        return tools;
    }
}
