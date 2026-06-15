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
import com.semantyca.jesoos.service.chat.ots.OtsGraph;
import com.semantyca.jesoos.service.chat.ots.OtsSessionManager;
import com.semantyca.jesoos.service.chat.tools.*;
import com.semantyca.jesoos.service.chat.tools.ad.CreateAdTool;
import com.semantyca.jesoos.service.chat.tools.ad.CreateAdToolHandler;
import com.semantyca.jesoos.service.chat.tools.auth.*;
import com.semantyca.jesoos.service.chat.tools.ots.StartOneTimeStreamTool;
import com.semantyca.jesoos.service.chat.tools.ots.StartOneTimeStreamToolHandler;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.SongEmitter;
import com.semantyca.jesoos.service.live.scripting.PerplexitySearchHelper;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.ws.PublicChatController;
import io.quarkus.mailer.reactive.ReactiveMailer;
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
    @Inject PlaylistQueueService playlistQueueService;
    @Inject BrandService brandService;
    @Inject UserService userService;
    @Inject PublicChatSessionManager sessionManager;
    @Inject KeycloakAuthService keycloakAuthService;
    @Inject PublicChatController controller;
    @Inject MetricPublisher metricPublisher;
    @Inject ReactiveMailer reactiveMailer;
    @Inject AiAgentService aiAgentService;
    @Inject BrandPool brandPool;
    @Inject SongEmitter songEmitter;
    @Inject SoundFragmentService soundFragmentService;
    @Inject IntroTtsGenerator introTtsGenerator;
    @Inject InternalRestCall internalRestCall;
    @Inject OneTimeStreamService oneTimeStreamService;
    @Inject ScriptService scriptService;
    @Inject OtsSessionManager otsSessionManager;
    @Inject OtsGraph otsGraph;
    @Inject AdSessionManager adSessionManager;
    @Inject AdGraph adGraph;
    @Inject EventService eventService;
    @Inject ListenerLabelCache listenerLabelCache;
    @Inject PerplexitySearchHelper perplexitySearchHelper;
    @Inject ChatService publicChatService;
    @Inject ChatAuthService chatAuthService;
    @Inject com.semantyca.jesoos.service.agenda.AgendaViewService agendaViewService;

    private ChatLlmClient llmClient;
    private LlmProviderAdapter llmProviderAdapter;
    private CompiledGraph<ChatState> compiledGraph;

    @PostConstruct
    void init() {
        try {
            String provider = config.getLlmProvider();
            llmProviderAdapter = LlmProviderRegistry.resolve(provider);
            llmClient = llmProviderAdapter.createClient(config);

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

        Uni<String> queueUni = playlistQueueService.getQueueByBrandSlug(brandName)
                .map(queue -> {
                    if (queue == null || queue.isEmpty()) return "";
                    StringBuilder sb = new StringBuilder("[Live queue: ");
                    for (int i = 0; i < Math.min(queue.size(), 5); i++) {
                        io.vertx.core.json.JsonObject track = queue.getJsonObject(i);
                        if (track == null) continue;
                        if (i > 0) sb.append(" → ");
                        String title = track.getString("title", track.getString("name", "?"));
                        String artist = track.getString("artist", track.getString("artistName", ""));
                        sb.append("\"").append(title).append("\"");
                        if (!artist.isBlank()) sb.append(" by ").append(artist);
                    }
                    sb.append("]");
                    return sb.toString();
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
                    result.put(ChatState.CONTEXT_BLOCK, t.getItem1().trim());
                    result.put(ChatState.LISTENER_CONTEXT, t.getItem2().trim());
                    return result;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> llmNode(ChatState state) {
        boolean isAuthenticated = state.userId() != 0;
        String djLanguages = state.djLanguages();
        List<LlmTool> tools = getToolsForUser(isAuthenticated, djLanguages);

        String model = state.iteration() == 0
                ? llmProviderAdapter.modelFor(LlmUseCase.MAIN_CHAT)
                : llmProviderAdapter.modelFor(LlmUseCase.FOLLOW_UP);

        String base = state.systemPrompt()
                .replace("{{isAuthenticated}}", Boolean.toString(isAuthenticated));
        if (!isAuthenticated) {
            int gateIdx = base.indexOf("!! AUTHENTICATED ONLY");
            if (gateIdx >= 0) base = base.substring(0, gateIdx).trim();
        }
        String systemPrompt = base
                .replace("{{liveContext}}", state.contextBlock())
                .replace("{{listenerContext}}", state.listenerContext());

        LlmRequest request = LlmRequest.builder()
                .maxTokens(1024L)
                .system(systemPrompt)
                .messages(state.history())
                .model(model)
                .tools(tools)
                .build();

        return Uni.createFrom().completionStage(() -> llmClient.createMessage(request))
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

    private static String toolStatusMessage(String toolName) {
        return switch (toolName) {
            case "play_song_with_intro"       -> "Queueing your song...";
            case "start_auth"                 -> "Sending verification code...";
            case "verify_code"                -> "Verifying code...";
            case "upload_song"                -> "Uploading your track...";
            case "search_brand_sound_fragments" -> "Searching catalog...";
            case "get_brand_catalog_summary"  -> "Loading catalog...";
            case "start_one_time_stream"      -> "Setting up your personal stream...";
            case "create_ad"                  -> "Setting up your ad...";
            case "listener_data"              -> "Remembering...";
            case "stream_info"                -> "Checking stream...";
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
            case "search_brand_sound_fragments" -> SearchBrandSoundFragmentsToolHandler.execute(input, aiHelperService);
            case "get_brand_catalog_summary" -> GetBrandCatalogSummaryToolHandler.execute(input, aiHelperService);
            case "listener_data" -> ListenerDataToolHandler.execute(input, listenerService, listenerLabelCache, userId);
            case "find_community_member" -> FindCommunityMemberToolHandler.execute(input, listenerService, brandName, userId);
            case "inform_owner" -> SendEmailToOwnerToolHandler.execute(input, brandService, userService,
                    reactiveMailer, config.getFromAddress(), userId, brandName);
            case "upload_song" -> UploadSongToolHandler.execute(input, listenerService, userService,
                    soundFragmentService, aiHelperService, brandPool, songEmitter, aiAgentService,
                    listenerLabelCache, brandService, brandName, userId);
            case "play_song_with_intro" -> PlaySongWithIntroToolHandler.execute(input, aiAgentService,
                    brandPool, introTtsGenerator, internalRestCall);
            case "start_one_time_stream" -> StartOneTimeStreamToolHandler.execute(input, oneTimeStreamService,
                    scriptService, otsSessionManager, otsGraph, config.getStreamerHost(), djName, connectionId);
            case "create_ad" -> CreateAdToolHandler.execute(input, brandService, adSessionManager, adGraph,
                    userId, brandName, djName, connectionId);
            case "manage_events" -> ManageEventsToolHandler.execute(input, eventService, brandService, brandName);
            case "send_ui_command" -> {
                if ("show_upload_button".equals(input.getOrDefault("command", ""))) {
                    sessionManager.grantUploadPermission(userId);
                }
                yield SendUICommandToolHandler.execute(input, connectionId);
            }
            case "stream_info" -> StreamInfoToolHandler.execute(input, playlistQueueService, agendaViewService,
                    brandName, reactiveMailer, userService, userId, config.getFromAddress());
            case "perplexity_search" -> PerplexitySearchToolHandler.execute(input, perplexitySearchHelper);
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

    private List<LlmTool> getToolsForUser(boolean isAuthenticated, String djLanguages) {
        List<LlmTool> tools = new ArrayList<>();
        tools.add(SendEmailToOwnerTool.toTool());
        tools.add(StreamInfoTool.toTool());
        if (isAuthenticated) {
            tools.add(SearchBrandSoundFragments.toTool());
            tools.add(GetBrandCatalogSummary.toTool());
            tools.add(ListenerDataTool.toTool());
            tools.add(FindCommunityMemberTool.toTool());
            tools.add(UploadSongTool.toTool());
            tools.add(PlaySongWithIntroTool.toTool(djLanguages));
            tools.add(StartOneTimeStreamTool.toTool());
            tools.add(CreateAdTool.toTool());
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
