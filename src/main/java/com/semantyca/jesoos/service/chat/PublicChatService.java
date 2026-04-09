package com.semantyca.jesoos.service.chat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.repository.exception.ext.UserAlreadyExistsException;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.core.util.WebHelper;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.external.KeycloakAuthService;
import com.semantyca.jesoos.service.chat.tools.*;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.officeframe.service.LabelService;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    @Override
    protected ChatType getChatType() {
        return ChatType.PUBLIC;
    }

    @Inject
    UserService userService;

    @Inject
    LabelService labelService;

    @Inject
    ReactiveMailer reactiveMailer;

    @Inject
    KeycloakAuthService keycloakAuthService;

    @Setter
    private com.semantyca.jesoos.ws.PublicChatController controller;

    public Uni<RegistrationResult> registerListener(String email, String stationSlug) {
        return userService.findByEmail(email)
                .chain(user -> {
                    String userToken = UUID.randomUUID().toString();
                    sessionManager.storeUserToken(userToken, email);

                    if (user == null || user.getId() == 0) {
                        ListenerDTO dto = new ListenerDTO();
                        dto.setEmail(email);
                        return listenerService.upsert(null, dto, stationSlug, SuperUser.build())
                                .map(listenerDTO -> new RegistrationResult(listenerDTO.getUserId(), userToken));
                    }

                    return listenerService.getByUserId(user.getId())
                            .chain(listener -> {
                                if (listener != null) {
                                    return ensureUserIsListenerOfStation(user.getId(), stationSlug)
                                            .replaceWith(new RegistrationResult(user.getId(), userToken));
                                }
                                ListenerDTO dto = new ListenerDTO();
                                dto.setEmail(email);
                                return listenerService.upsert(null, dto, stationSlug, SuperUser.build())
                                        .map(listenerDTO -> new RegistrationResult(user.getId(), userToken));
                            });
                });
    }

    public Uni<IUser> authenticateUserFromToken(String token) {
        if (token == null || token.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Token is required"));
        }

        String email = sessionManager.validateSessionAndGetEmail(token);
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

    @Override
    protected List<Tool> getAvailableTools() {
        return getToolsForUser(true);
    }

    protected List<Tool> getToolsForUser(boolean isAuthenticated) {
        List<Tool> tools = new ArrayList<>();
        tools.add(GetStations.toTool());
        tools.add(SendEmailToOwnerTool.toTool());
        
        if (isAuthenticated) {
            tools.add(SearchBrandSoundFragments.toTool());
            tools.add(AddToQueueTool.toTool());
            tools.add(PerplexitySearchTool.toTool());
            tools.add(AudienceTool.toTool());
            tools.add(ListenerDataTool.toTool());
        } else {
            tools.add(StartAuthTool.toTool());
            tools.add(VerifyCode.toTool());
        }
        
        return tools;
    }

    @Override
    protected MessageCreateParams buildMessageCreateParams(String renderedPrompt, List<MessageParam> history) {
        return buildMessageCreateParamsForUser(renderedPrompt, history, true);
    }

    protected MessageCreateParams buildMessageCreateParamsForUser(String renderedPrompt, List<MessageParam> history, boolean isAuthenticated) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(1024L)
                .system(renderedPrompt)
                .messages(history)
                .model(Model.CLAUDE_HAIKU_4_5_20251001);

        for (Tool tool : getToolsForUser(isAuthenticated)) {
            builder.addTool(tool);
        }

        return builder.build();
    }
    
    @Override
    public Uni<Void> generateBotResponse(String userMessage, Consumer<String> chunkHandler, Consumer<String> completionHandler, String connectionId, String slugName, IUser user) {
        boolean isAuthenticated = !(user instanceof AnonymousUser) && user.getId() != 0;
        
        MessageParam userMsg = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofString(userMessage))
                .build();

        chatRepository.appendToConversation(user.getId(), getChatType(), userMsg);

        Uni<com.semantyca.mixpla.model.brand.Brand> stationUni = brandService.getBySlugName(slugName);

        return stationUni.flatMap(station -> {
            String radioStationName = station != null && station.getLocalizedName() != null
                    ? station.getLocalizedName().getOrDefault(LanguageCode.en, station.getSlugName())
                    : slugName;

            Uni<com.semantyca.mixpla.model.aiagent.AiAgent> agentUni;
            if (station != null && station.getAiAgentId() != null) {
                agentUni = aiAgentService.getById(station.getAiAgentId(), SuperUser.build(), LanguageCode.en);
            } else {
                agentUni = Uni.createFrom().item(() -> null);
            }

            return agentUni.onItem().transform(agent -> {
                String djName = agent.getName();

                assert station != null;
                String stationSlug = station.getSlugName();
                String stationCountry = station.getCountry().getCountryName();
                String stationBitRate = Long.toString(station.getBitRate());
                String stationStatus = "unknown";
                String stationTz = station.getTimeZone().getId();
                String stationDesc = station.getDescription();
                String hlsUrl = config.getHost() + "/" + stationSlug + "/radio/stream.m3u8";
                String mixplaUrl = "https://player.mixpla.io/?radio=" + stationSlug;

                String djLanguages, djPrimaryVoices;
                String djCopilotName = "";
                djLanguages = agent.getPreferredLang().stream()
                        .sorted(java.util.Comparator.comparingDouble(com.semantyca.mixpla.model.aiagent.LanguagePreference::getWeight).reversed())
                        .map(lp -> lp.getLanguageTag().name())
                        .reduce((a, b) -> a + "," + b).orElse("");
                djPrimaryVoices = agent.getTtsSetting().getDj().getId();

                String renderedPrompt = getMainPrompt()
                        .replace("{{djName}}", djName)
                        .replace("{{radioStationName}}", radioStationName)
                        .replace("{{radioStationSlug}}", stationSlug)
                        .replace("{{radioStationCountry}}", stationCountry)
                        .replace("{{radioStationBitRate}}", stationBitRate)
                        .replace("{{radioStationStatus}}", stationStatus)
                        .replace("{{radioStationTimeZone}}", stationTz)
                        .replace("{{radioStationDescription}}", stationDesc)
                        .replace("{{radioStationHlsUrl}}", hlsUrl)
                        .replace("{{radioStationMixplaUrl}}", mixplaUrl)
                        .replace("{{djLanguages}}", djLanguages)
                        .replace("{{djCopilotName}}", djCopilotName)
                        .replace("{{userName}}", user.getUserName());

                assistantNameByConnectionId.put(connectionId, djName);
                assistantNameByConnectionId.put(connectionId + "_voice", djPrimaryVoices);

                return loadConversationHistoryWithSummary(user.getId(), slugName, getChatType())
                        .map(history -> buildMessageCreateParamsForUser(renderedPrompt, history, isAuthenticated));
            });
        }).flatMap(paramsUni -> paramsUni).flatMap(params ->
                Uni.createFrom().completionStage(() -> anthropicClient.async().messages().create(params))
                        .flatMap(message -> {
                            Optional<ToolUseBlock> toolUse = message.content().stream()
                                    .flatMap(block -> block.toolUse().stream())
                                    .findFirst();

                            if (toolUse.isPresent()) {
                                List<MessageParam> history = chatRepository.getConversationHistory(user.getId(), getChatType());
                                return handleToolCall(toolUse.get(), chunkHandler, completionHandler, connectionId, slugName, user.getId(), history);
                            } else {
                                return streamResponse(params, chunkHandler, completionHandler, connectionId, slugName, user.getId());
                            }
                        })
        ).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
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
                        List<MessageParam> history = chatRepository.getConversationHistory(userId, getChatType());
                        return handleToolCall(toolUse.get(), chunkHandler, completionHandler, connectionId, brandName, userId, history);
                    } else {
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

        return switch (toolUse.name()) {
            case "get_stations" -> GetStationsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
            case "search_brand_sound_fragments" -> SearchBrandSoundFragmentsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
            case "perplexity_search" -> PerplexitySearchToolHandler.handle(
                    toolUse, inputMap, perplexitySearchHelper, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
            case "listener" -> AudienceToolHandler.handle(
                    toolUse, inputMap, listenerService, brandName, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
            case "listener_data" -> ListenerDataToolHandler.handle(
                    toolUse, inputMap, listenerService, userId, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
            case "send_email_to_owner" -> SendEmailToOwnerToolHandler.handle(
                    toolUse, inputMap, brandService, userService, reactiveMailer, config.getFromAddress(), userId, brandName, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
            case "start_auth" -> StartAuthToolHandler.handle(
                    toolUse, inputMap, keycloakAuthService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
            case "verify_code" -> VerifyCodeToolHandler.handle(
                    toolUse, inputMap, sessionManager, userService, controller, this, brandName, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
            default -> Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
        };
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
}
