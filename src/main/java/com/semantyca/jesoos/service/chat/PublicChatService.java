package com.semantyca.jesoos.service.chat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.semantyca.core.model.UserData;
import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.external.KeycloakAuthService;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.EventService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.PlaylistQueueService;
import com.semantyca.jesoos.service.chat.tools.*;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.ScenePool;
import com.semantyca.jesoos.service.live.SongEmitter;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.ws.PublicChatController;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Setter;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

@ApplicationScoped
public class PublicChatService extends ChatService {

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
    PlaylistQueueService playlistQueueService;

    @Setter
    private PublicChatController controller;

    public Uni<RegistrationResult> registerListener(String email, String stationSlug, String preferredName) {
        String userToken = UUID.randomUUID().toString();
        return sessionManager.storeUserToken(userToken, email)
                .chain(() -> userService.findByEmail(email))
                .chain(user -> {
                    if (user == null || user.getId() == 0) {
                        ListenerDTO dto = new ListenerDTO();
                        dto.setEmail(email);
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
                                dto.setEmail(email);
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
                    return userService.findByEmail(email)
                            .onItem().transformToUni(user -> {
                                if (user == null || user.getId() == 0) {
                                    return Uni.createFrom().item(AnonymousUser.build());
                                }
                                return Uni.createFrom().item(user);
                            });
                });
    }

    public Function<MessageCreateParams, Uni<Void>> createAuthStreamFn(
            Consumer<String> chunkHandler, Consumer<String> completionHandler,
            String connectionId, String brandName, long userId) {
        return createStreamFunction(chunkHandler, completionHandler, connectionId, brandName, userId);
    }

    public Uni<String> resolveDisplayName(long userId, String fallback) {
        return listenerService.resolveDisplayName(userId, fallback);
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
    protected List<Tool> getToolsForUser(boolean isAuthenticated) {
        List<Tool> tools = new ArrayList<>();
        tools.add(SendEmailToOwnerTool.toTool());
        
        if (isAuthenticated) {
            tools.add(SearchBrandSoundFragments.toTool());
            tools.add(GetBrandCatalogSummary.toTool());
            tools.add(PerplexitySearchTool.toTool());
            tools.add(ListenerDataTool.toTool());
            tools.add(FindCommunityMemberTool.toTool());
            tools.add(LiveStreamInfoTool.toTool());
            tools.add(UploadSongTool.toTool());
            tools.add(PlaySongWithIntroTool.toTool());
            tools.add(ManageEventsTool.toTool());
            tools.add(SendUICommandTool.toTool());
        } else {
            tools.add(StartAuthTool.toTool());
            tools.add(VerifyCode.toTool());
        }
        
        return tools;
    }

    @Override
    protected MessageCreateParams buildMessageCreateParams(String renderedPrompt, List<MessageParam> history, IUser user) {
        boolean isAuthenticated = user.getEmail() != null && !user.getEmail().isBlank();
        return buildMessageCreateParamsForUser(renderedPrompt, history, isAuthenticated);
    }

    protected MessageCreateParams buildMessageCreateParamsForUser(String renderedPrompt, List<MessageParam> history, boolean isAuthenticated) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(1024L)
                .system(renderedPrompt)
                .messages(history)
                .model(Model.CLAUDE_HAIKU_4_5_20251001);

        List<Tool> tools = getToolsForUser(isAuthenticated);
        tools.forEach(t -> builder.addTool(t));

        ChatLogger.tools(isAuthenticated, history.size(),
                tools.stream().map(Tool::name).reduce((a, b) -> a + "," + b).orElse("none"));

        return builder.build();
    }
    
    @Override
    protected java.util.function.Function<MessageCreateParams, Uni<Void>> createStreamFunction(
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {
        return params -> handleFollowUpWithToolDetection(params, chunkHandler, completionHandler, connectionId, brandName, userId);
    }
    
    @Override
    protected Uni<Void> handleFollowUpWithToolDetection(
            MessageCreateParams params,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {
        
        boolean isAuthenticated = userId != 0;
        
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(params.maxTokens())
                .system(java.util.Objects.requireNonNull(params.system().orElse(null)))
                .messages(params.messages())
                .model(params.model());
        
        for (Tool tool : getToolsForUser(isAuthenticated)) {
            builder.addTool(tool);
        }
        
        MessageCreateParams paramsWithTools = builder.build();
        
        return Uni.createFrom().completionStage(() -> anthropicClient.async().messages().create(paramsWithTools))
                .flatMap(message -> {
                    Optional<ToolUseBlock> toolUse = message.content().stream()
                            .flatMap(block -> block.toolUse().stream())
                            .findFirst();

                    if (toolUse.isPresent()) {
                        ChatLogger.followUp(toolUse.get().name());
                        List<MessageParam> history = chatRepository.getConversationHistory(ChatRepository.sessionKey(userId, connectionId, getChatType()));
                        return handleToolCall(toolUse.get(), chunkHandler, completionHandler, connectionId, brandName, userId, history);
                    } else {
                        ChatLogger.followUpNoTool();
                        return streamResponse(params, chunkHandler, completionHandler, connectionId, brandName, userId);
                    }
                }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    @Override
    protected Uni<Void> handleToolCall(ToolUseBlock toolUse,
                                       Consumer<String> chunkHandler,
                                       Consumer<String> completionHandler,
                                       String connectionId,
                                       String brandName,
                                       long userId,
                                       List<MessageParam> conversationHistory) {

        Map<String, JsonValue> inputMap = extractInputMap(toolUse);
        Function<MessageCreateParams, Uni<Void>> streamFn =
                createStreamFunction(chunkHandler, completionHandler, connectionId, brandName, userId);

        String djName = assistantNameByConnectionId.getOrDefault(connectionId, "");
        String resolvedFollowUpPrompt = getFollowUpPrompt().replace("{{djName}}", djName);

        return switch (toolUse.name()) {
            case "search_brand_sound_fragments" -> SearchBrandSoundFragmentsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "get_brand_catalog_summary" -> GetBrandCatalogSummaryToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "perplexity_search" -> PerplexitySearchToolHandler.handle(
                    toolUse, inputMap, perplexitySearchHelper, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
case "listener_data" -> ListenerDataToolHandler.handle(
                    toolUse, inputMap, listenerService, listenerLabelCache, userId, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "upload_song" -> UploadSongToolHandler.handle(
                    toolUse, inputMap, listenerService, userService, soundFragmentService, aiHelperService, brandPool, songEmitter, aiAgentService, listenerLabelCache, brandName, userId, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "live_stream_info" -> LiveStreamInfoToolHandler.handle(
                    toolUse, inputMap, brandPool, scenePool, playlistQueueService, brandName, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "find_community_member" -> FindCommunityMemberToolHandler.handle(
                    toolUse, inputMap, listenerService, brandName, userId, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "send_email_to_owner" -> SendEmailToOwnerToolHandler.handle(
                    toolUse, inputMap, brandService, userService, reactiveMailer, config.getFromAddress(), userId, brandName, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "start_auth" -> StartAuthToolHandler.handle(
                    toolUse, inputMap, keycloakAuthService, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "verify_code" -> VerifyCodeToolHandler.handle(
                    toolUse, inputMap, sessionManager, userService, controller, this, brandName, chunkHandler, completionHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "play_song_with_intro" -> PlaySongWithIntroToolHandler.handle(
                    toolUse, inputMap, soundFragmentService, aiAgentService, brandPool, songEmitter, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "manage_events" -> ManageEventsToolHandler.handle(
                    toolUse, inputMap, eventService, brandService, brandName, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            case "send_ui_command" -> SendUICommandToolHandler.handle(
                    toolUse, inputMap, chunkHandler, connectionId, conversationHistory, resolvedFollowUpPrompt, streamFn
            );
            default -> Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
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
    protected List<Tool> getAvailableTools() {
        return getToolsForUser(true);
    }

    @Override
    protected ChatType getChatType() {
        return ChatType.PUBLIC;
    }

}
