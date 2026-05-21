package com.semantyca.jesoos.service.chat;

import com.semantyca.core.model.UserData;
import com.semantyca.core.model.cnst.MessageType;
import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.external.KeycloakAuthService;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.EventService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.PlaylistQueueService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmTool;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.chat.ots.OtsGraph;
import com.semantyca.jesoos.service.chat.ots.OtsResult;
import com.semantyca.jesoos.service.chat.ots.OtsSessionManager;
import com.semantyca.jesoos.service.chat.tools.*;
import com.semantyca.jesoos.service.chat.tools.auth.LogoffTool;
import com.semantyca.jesoos.service.chat.tools.auth.LogoffToolHandler;
import com.semantyca.jesoos.service.chat.tools.auth.StartAuthTool;
import com.semantyca.jesoos.service.chat.tools.auth.StartAuthToolHandler;
import com.semantyca.jesoos.service.chat.tools.auth.VerifyCode;
import com.semantyca.jesoos.service.chat.tools.auth.VerifyCodeToolHandler;
import com.semantyca.jesoos.service.OneTimeStreamService;
import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.jesoos.service.chat.tools.ots.StartOneTimeStreamTool;
import com.semantyca.jesoos.service.chat.tools.ots.StartOneTimeStreamToolHandler;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.ScenePool;
import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.SongEmitter;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.util.EmailUtil;
import com.semantyca.jesoos.ws.PublicChatController;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Setter;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class PublicChatService extends ChatService {
    private static final Logger LOGGER = Logger.getLogger(PublicChatService.class);

    protected PublicChatService() {
        super(null, null);
    }

    @Inject
    public PublicChatService(JesoosConfig config, AiHelperService aiHelperService) {
        super(config, aiHelperService);
    }

    @Inject
    PublicChatSessionManager sessionManager;

    @Inject
    ListenerService listenerService;

    @Inject
    BrandService brandService;

    @Inject
    EventService eventService;

    @Inject
    UserService userService;

    @Inject
    ReactiveMailer reactiveMailer;

    @Inject
    KeycloakAuthService keycloakAuthService;

    @Inject
    SoundFragmentService soundFragmentService;

    @Inject
    ListenerLabelCache listenerLabelCache;

    @Inject
    BrandPool brandPool;

    @Inject
    ScenePool scenePool;

    @Inject
    SongEmitter songEmitter;

    @Inject
    IntroTtsGenerator introTtsGenerator;

    @Inject
    InternalRestCall internalRestCall;

    @Inject
    PlaylistQueueService playlistQueueService;

    @Inject
    MetricPublisher metricPublisher;

    @Inject
    OneTimeStreamService oneTimeStreamService;

    @Inject
    ScriptService scriptService;

    @Inject
    OtsSessionManager otsSessionManager;

    @Inject
    OtsGraph otsGraph;

    @Inject
    PublicChatIntentRouter intentRouter;

    @Setter
    private PublicChatController controller;

    public Uni<RegistrationResult> registerListener(String email, String stationSlug, String preferredName) {
        String normalizedEmail = EmailUtil.normalize(email);
        String userToken = UUID.randomUUID().toString();
        return sessionManager.storeUserToken(userToken, normalizedEmail)
                .chain(() -> userService.findByEmail(normalizedEmail))
                .chain(user -> {
                    if (user == null || user.getId() == 0) {
                        ListenerDTO dto = new ListenerDTO();
                        dto.setEmail(normalizedEmail);
                        if (preferredName != null && !preferredName.isBlank()) {
                            dto.setUserData(Map.of("preferred_name", preferredName));
                        }
                        return listenerService.upsert(null, dto, stationSlug, SuperUser.build())
                                .map(listenerDTO -> new RegistrationResult(listenerDTO.getUserId(), userToken));
                    }

                    return listenerService.getByUserId(user.getId())
                            .chain(listener -> {
                                if (listener != null) {
                                    Uni<Void> storeNameUni = (preferredName != null && !preferredName.isBlank())
                                            ? listenerService.updateUserData(listener.getId(),
                                                    mergeUserData(listener.getUserData(), "preferred_name", preferredName))
                                            : Uni.createFrom().voidItem();
                                    return storeNameUni
                                            .chain(() -> ensureUserIsListenerOfStation(user.getId(), stationSlug))
                                            .replaceWith(new RegistrationResult(user.getId(), userToken));
                                }
                                ListenerDTO dto = new ListenerDTO();
                                dto.setEmail(normalizedEmail);
                                if (preferredName != null && !preferredName.isBlank()) {
                                    dto.setUserData(Map.of("preferred_name", preferredName));
                                }
                                return listenerService.upsert(null, dto, stationSlug, SuperUser.build())
                                        .map(listenerDTO -> new RegistrationResult(user.getId(), userToken));
                            });
                });
    }

    public Uni<IUser> authenticateUserFromToken(String token) {
        if (token == null || token.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Token is required"));
        }

        return sessionManager.validateSessionAndGetEmail(token)
                .onItem().transformToUni(email -> {
                    if (email == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Invalid or expired token"));
                    }
                    return userService.findByEmail(EmailUtil.normalize(email))
                            .onItem().transformToUni(user -> {
                                if (user == null || user.getId() == 0) {
                                    return Uni.createFrom().item(AnonymousUser.build());
                                }
                                return Uni.createFrom().item(user);
                            });
                });
    }

    public Function<LlmRequest, Uni<Void>> createAuthStreamFn(
            Consumer<String> chunkHandler, Consumer<String> completionHandler,
            String connectionId, String brandName, long userId) {
        return createStreamFunction(chunkHandler, completionHandler, connectionId, brandName, userId);
    }

    public Uni<String> resolveDisplayName(long userId, String fallback) {
        return listenerService.resolveDisplayName(userId, fallback);
    }

    @Override
    protected Uni<String> resolveUserLabel(IUser user) {
        if (user.getId() == 0) return Uni.createFrom().item("");
        return listenerService.resolveDisplayName(user.getId(), null);
    }

    public Uni<Void> ensureUserIsListenerOfStation(long userId, String stationSlug) {
        return listenerService.getByUserId(userId)
                .chain(listener -> {
                    if (listener == null) {
                        return Uni.createFrom().voidItem();
                    }

                    return brandService.getBySlugName(stationSlug)
                            .chain(station -> {
                                if (station == null) {
                                    return Uni.createFrom().voidItem();
                                }

                                return listenerService.getListenersBrands(listener.getId())
                                        .chain(currentStations -> {
                                            if (!currentStations.contains(station.getId())) {
                                                return listenerService.addBrandToListener(listener.getId(), station.getId());
                                            }
                                            return Uni.createFrom().voidItem();
                                        });
                            });
                });
    }

    protected List<LlmTool> getToolsForUser(boolean isAuthenticated, String djLanguages) {
        List<LlmTool> tools = new ArrayList<>();
        tools.add(SendEmailToOwnerTool.toTool());

        if (isAuthenticated) {
            tools.add(SearchBrandSoundFragments.toTool());
            tools.add(GetBrandCatalogSummary.toTool());
            // tools.add(PerplexitySearchTool.toTool());
            tools.add(ListenerDataTool.toTool());
            tools.add(FindCommunityMemberTool.toTool());
            tools.add(LiveStreamInfoTool.toTool());
            tools.add(UploadSongTool.toTool());
            tools.add(PlaySongWithIntroTool.toTool(djLanguages));
            tools.add(StartOneTimeStreamTool.toTool());
            tools.add(ManageEventsTool.toTool());
            tools.add(SendUICommandTool.toTool());
            tools.add(LogoffTool.toTool());
        } else {
            tools.add(LiveStreamInfoTool.toTool());
            tools.add(StartAuthTool.toTool());
            tools.add(VerifyCode.toTool());
        }

        return tools;
    }

    @Override
    protected LlmRequest buildLlmRequest(String renderedPrompt, List<LlmMessage> history, IUser user, String djLanguages) {
        boolean isAuthenticated = user.getEmail() != null && !user.getEmail().isBlank();
        return buildLlmRequestForUser(renderedPrompt, history, isAuthenticated, djLanguages);
    }

    protected LlmRequest buildLlmRequestForUser(String renderedPrompt, List<LlmMessage> history, boolean isAuthenticated, String djLanguages) {
        List<LlmTool> tools = getToolsForUser(isAuthenticated, djLanguages);

        ChatLogger.tools(isAuthenticated, history.size(),
                tools.stream().map(LlmTool::name).reduce((a, b) -> a + "," + b).orElse("none"));

        return LlmRequest.builder()
                .maxTokens(1024L)
                .system(renderedPrompt)
                .messages(history)
                .model(resolveMainModel())
                .tools(tools)
                .build();
    }

    protected Function<LlmRequest, Uni<Void>> createStreamFunction(
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {
        return params -> handleFollowUpWithToolDetection(params, chunkHandler, completionHandler, connectionId, brandName, userId);
    }

    protected Uni<Void> handleFollowUpWithToolDetection(
            LlmRequest request,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {

        boolean isAuthenticated = userId != 0;
        String djLanguages = assistantNameByConnectionId.getOrDefault(connectionId + "_lang", "");

        LlmRequest requestWithTools = request.toBuilder()
                .tools(getToolsForUser(isAuthenticated, djLanguages))
                .model(resolveFollowUpModel())
                .build();

        return Uni.createFrom().completionStage(() -> llmClient.createMessage(requestWithTools))
                .flatMap(response -> {
                    if (response.toolCall().isPresent()) {
                        LlmToolCall toolCall = response.toolCall().get();
                        ChatLogger.followUp(toolCall.name());
                        List<LlmMessage> history = chatRepository.getConversationHistory(
                                ChatRepository.sessionKey(userId, connectionId, getChatType()));
                        LOGGER.infof("[followUp] tool=%s userId=%d historySize=%d lastRole=%s",
                                toolCall.name(), userId, history.size(),
                                history.isEmpty() ? "n/a" : history.getLast().role().name());
                        return handleToolCall(toolCall, chunkHandler, completionHandler, connectionId, brandName, userId, history);
                    } else {
                        ChatLogger.followUpNoTool();
                        return streamResponse(requestWithTools, chunkHandler, completionHandler, connectionId, brandName, userId);
                    }
                }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    @Override
    protected Uni<Void> handleToolCall(LlmToolCall toolCall,
                                       Consumer<String> chunkHandler,
                                       Consumer<String> completionHandler,
                                       String connectionId,
                                       String brandName,
                                       long userId,
                                       List<LlmMessage> conversationHistory) {

        Map<String, Object> inputMap = toolCall.input();
        Function<LlmRequest, Uni<Void>> streamFn =
                createStreamFunction(chunkHandler, completionHandler, connectionId, brandName, userId);

        String djName = assistantNameByConnectionId.getOrDefault(connectionId, "");
        String resolvedFollowUpPrompt = getFollowUpPrompt().replace("{{djName}}", djName);

        return switch (toolCall.name()) {
            case "search_brand_sound_fragments" -> SearchBrandSoundFragmentsToolHandler.handle(
                    toolCall, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "get_brand_catalog_summary" -> GetBrandCatalogSummaryToolHandler.handle(
                    toolCall, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "perplexity_search" -> PerplexitySearchToolHandler.handle(
                    toolCall, inputMap, perplexitySearchHelper, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "listener_data" -> ListenerDataToolHandler.handle(
                    toolCall, inputMap, listenerService, listenerLabelCache, userId, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "upload_song" -> UploadSongToolHandler.handle(
                    toolCall, inputMap, listenerService, userService, soundFragmentService, aiHelperService, brandPool, songEmitter, aiAgentService, listenerLabelCache, brandService, brandName, userId, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "live_stream_info" -> LiveStreamInfoToolHandler.handle(
                    toolCall, inputMap, playlistQueueService, brandName, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "find_community_member" -> FindCommunityMemberToolHandler.handle(
                    toolCall, inputMap, listenerService, brandName, userId, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "send_email_to_owner" -> SendEmailToOwnerToolHandler.handle(
                    toolCall, inputMap, brandService, userService, reactiveMailer, config.getFromAddress(), userId, brandName, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "start_auth" -> StartAuthToolHandler.handle(
                    toolCall, inputMap, keycloakAuthService, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "verify_code" -> VerifyCodeToolHandler.handle(
                    toolCall, inputMap, sessionManager, userService, controller, this, brandName, metricPublisher, chunkHandler, completionHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "play_song_with_intro" -> PlaySongWithIntroToolHandler.handle(
                    toolCall, inputMap, aiAgentService, brandPool, introTtsGenerator, internalRestCall, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "start_one_time_stream" -> StartOneTimeStreamToolHandler.handle(
                    toolCall, inputMap, oneTimeStreamService, scriptService, otsSessionManager, otsGraph,
                    config.getStreamerHost(), assistantNameByConnectionId.getOrDefault(connectionId, "DJ"),
                    chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "manage_events" -> ManageEventsToolHandler.handle(
                    toolCall, inputMap, eventService, brandService, brandName, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "send_ui_command" -> {
                if ("show_upload_button".equals(inputMap.getOrDefault("command", ""))) {
                    sessionManager.grantUploadPermission(userId);
                }
                yield SendUICommandToolHandler.handle(
                        toolCall, inputMap, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
                );
            }
            case "logoff" -> LogoffToolHandler.handle(
                    toolCall, inputMap, sessionManager, userService, controller, this, metricPublisher, brandName, userId, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            default -> Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolCall.name()));
        };
    }

    private UserData mergeUserData(UserData existing, String key, String value) {
        UserData merged = new UserData(existing != null && existing.getData() != null
                ? new java.util.HashMap<>(existing.getData())
                : new java.util.HashMap<>());
        merged.getData().put(key, value);
        return merged;
    }

    @Override
    protected String getMainPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/mainPrompt.hbs");
            return !custom.isBlank() ? custom : super.getMainPrompt();
        } catch (Exception ignored) {
            return super.getMainPrompt();
        }
    }

    @Override
    protected String getFollowUpPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/followUpPrompt.hbs");
            return !custom.isBlank() ? custom : super.getFollowUpPrompt();
        } catch (Exception ignored) {
            return super.getFollowUpPrompt();
        }
    }

    @Override
    protected List<LlmTool> getAvailableTools() {
        return getToolsForUser(true, "");
    }

    @Override
    public Uni<Void> generateBotResponse(String userMessage, Consumer<String> chunkHandler,
                                         Consumer<String> completionHandler,
                                         String connectionId, String slugName, IUser user) {
        return intentRouter.decide(connectionId, userMessage)
                .flatMap(decision -> {
                    if (decision.intent() == ChatIntent.START_OTS && otsSessionManager.isActive(connectionId)) {
                        return executeOtsContinuation(userMessage, chunkHandler, completionHandler, connectionId, slugName, user);
                    }
                    return super.generateBotResponse(userMessage, chunkHandler, completionHandler, connectionId, slugName, user);
                });
    }

    private Uni<Void> executeOtsContinuation(String userMessage, Consumer<String> chunkHandler,
                                              Consumer<String> completionHandler,
                                              String connectionId, String slugName, IUser user) {
        return otsGraph.processUserTurn(connectionId, userMessage)
                .flatMap(result -> {
                    String djName = assistantNameByConnectionId.getOrDefault(connectionId, "DJ");
                    String responseText = result.action() == OtsResult.Action.STREAM_STARTED
                            ? "Your stream is live! Tune in here: " + result.mixplaUrl()
                            : result.question();
                    return sendOtsResponse(responseText, djName, connectionId, user.getId(), slugName, chunkHandler, completionHandler);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.errorf("[OTS] processUserTurn failed connectionId=%s: %s", connectionId, err.getMessage());
                    otsSessionManager.end(connectionId);
                    chunkHandler.accept(com.semantyca.jesoos.dto.ChatMessageDTO.processingDone(connectionId).build().toJson());
                    completionHandler.accept(com.semantyca.jesoos.dto.ChatMessageDTO.error("Stream setup failed, please try again.", "system", connectionId).build().toJson());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> sendOtsResponse(String text, String djName, String connectionId,
                                      long userId, String slugName,
                                      Consumer<String> chunkHandler, Consumer<String> completionHandler) {
        return Uni.createFrom().voidItem()
                .invoke(() -> {
                    chunkHandler.accept(com.semantyca.jesoos.dto.ChatMessageDTO.chunk(text, djName, connectionId).build().toJson());
                    chunkHandler.accept(com.semantyca.jesoos.dto.ChatMessageDTO.processingDone(connectionId).build().toJson());

                    chatRepository.appendToConversation(
                            ChatRepository.sessionKey(userId, connectionId, getChatType()),
                            LlmMessage.text(LlmMessage.Role.ASSISTANT, text));

                    JsonObject botMessage = createMessage(MessageType.BOT, djName, text, System.currentTimeMillis(), connectionId);
                    chatRepository.saveChatMessage(userId, slugName, getChatType(), botMessage)
                            .subscribe().with(success -> {}, err -> LOGGER.error("Failed to save OTS bot message", err));

                    completionHandler.accept(com.semantyca.jesoos.dto.ChatMessageDTO.bot(text, djName, connectionId)
                            .timestamp(System.currentTimeMillis()).build().toJson());
                })
                .runSubscriptionOn(getDefaultWorkerPool());
    }

    @Override
    protected ChatType getChatType() {
        return ChatType.PUBLIC;
    }
}
