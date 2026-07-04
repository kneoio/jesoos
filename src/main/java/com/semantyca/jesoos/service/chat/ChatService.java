package com.semantyca.jesoos.service.chat;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.MessageType;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.model.chat.ChatMessageEnvelope;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.ad.AdContinuationHandler;
import com.semantyca.jesoos.service.chat.ad.AdSessionManager;
import com.semantyca.jesoos.service.chat.llm.ChatLlmClient;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmProviderAdapter;
import com.semantyca.jesoos.service.chat.llm.LlmProviderRegistry;
import com.semantyca.jesoos.service.chat.ots.OtsContinuationHandler;
import com.semantyca.jesoos.service.chat.ots.OtsScriptsProvider;
import com.semantyca.jesoos.service.chat.ots.OtsSessionManager;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.ScenePool;
import com.semantyca.jesoos.service.maintenance.ChatSummaryService;
import com.semantyca.jesoos.ws.PublicChatController;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Setter;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.function.Consumer;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class ChatService {
    private static final Logger LOGGER = Logger.getLogger(ChatService.class);

    protected final ChatLlmClient llmClient;
    protected final LlmProviderAdapter llmProviderAdapter;
    protected final AiHelperService aiHelperService;
    protected final String mainPrompt;
    protected final JesoosConfig config;
    protected final ConcurrentHashMap<String, String> assistantNameByConnectionId = new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<String, BrandStaticData> brandStaticCache = new ConcurrentHashMap<>();

    @Inject
    protected ScenePool scenePool;
    @Inject
    protected BrandService brandService;
    @Inject
    protected AiAgentService aiAgentService;
    @Inject
    protected ChatRepository chatRepository;
    @Inject
    protected ChatSummaryService chatSummaryService;
    @Inject
    ListenerService listenerService;
    @Inject
    UserService userService;
    @Inject
    OtsSessionManager otsSessionManager;
    @Inject
    AdSessionManager adSessionManager;
    @Inject
    OtsScriptsProvider otsScriptsProvider;
    @Inject
    OtsContinuationHandler otsContinuationHandler;
    @Inject
    AdContinuationHandler adContinuationHandler;
    @Inject
    PublicChatIntentRouter intentRouter;
    @Inject
    ChatAgent chatAgent;
    @Inject
    ChatAuthService chatAuthService;
    @Inject
    MetricPublisher metricPublisher;
    @Inject
    ChatSessionTracer chatSessionTracer;

    @Setter
    private PublicChatController controller;

    protected ChatService() {
        this.llmClient = null;
        this.llmProviderAdapter = null;
        this.aiHelperService = null;
        this.config = null;
        this.mainPrompt = null;
    }

    @Inject
    public ChatService(JesoosConfig config, AiHelperService aiHelperService) {
        if (config != null) {
            String provider = config.getLlmProvider();
            LOGGER.infof("[llm] provider=%s", provider);
            this.llmProviderAdapter = LlmProviderRegistry.resolve(provider);
            this.llmClient = llmProviderAdapter.createClient(config);
            this.aiHelperService = aiHelperService;
            this.config = config;
            this.mainPrompt = ResourceUtil.loadResourceAsString("prompts/mainPrompt.hbs");
        } else {
            this.llmClient = null;
            this.llmProviderAdapter = null;
            this.aiHelperService = null;
            this.config = null;
            this.mainPrompt = null;
        }
    }

    public Uni<String> processUserMessage(String username, String content, String connectionId, String brandName, IUser user) {
        return Uni.createFrom().item(() -> {
            ChatMessageEnvelope message = ChatMessageEnvelope.of(MessageType.USER, username, content, System.currentTimeMillis(), connectionId);

            chatRepository.saveChatMessage(user.getId(), brandName, ChatType.PUBLIC, message).subscribe().with(
                    success -> {},
                    failure -> LOGGER.error("Failed to save user message", failure)
            );

            chatRepository.appendToConversation(ChatRepository.connectionKey(connectionId), LlmMessage.text(LlmMessage.Role.USER, content));

            return ChatMessageDTO.user(content, username, connectionId).build().toJson();
        });
    }

    public Uni<String> getChatHistory(String brandName, int limit, String connectionId, IUser user) {
        return chatRepository.getRecentChatMessages(user.getId(), connectionId, brandName, ChatType.PUBLIC, limit)
                .map(recentMessages -> {
                    JsonArray messages = new JsonArray();
                    recentMessages.forEach(messages::add);

                    JsonObject response = new JsonObject()
                            .put("type", "history")
                            .put("messages", messages);

                    return response.encode();
                });
    }

    public Uni<Void> generateBotResponse(String userMessage, Consumer<String> chunkHandler, Consumer<String> completionHandler, String connectionId, String slugName, IUser user) {
        UUID traceId = chatSessionTracer.resolveTraceId(slugName);
        metricPublisher.publishMetric(slugName, MetricEventType.DEBUG, ProcessType.FLOW,
                "chat_user", Map.of("message", userMessage, "userId", user.getId(), "connectionId", connectionId), traceId);
        return intentRouter.decide(connectionId, userMessage, slugName)
                .flatMap(decision -> {
                    String djName = assistantNameByConnectionId.getOrDefault(connectionId, "DJ");
                    if (decision.intent() == ChatIntent.START_OTS && otsSessionManager.isActive(connectionId)) {
                        return otsContinuationHandler.execute(userMessage, djName, user, connectionId, slugName, chunkHandler, completionHandler);
                    }
                    if (decision.intent() == ChatIntent.CREATE_AD && adSessionManager.isActive(connectionId)) {
                        return adContinuationHandler.execute(userMessage, djName, user, connectionId, slugName, chunkHandler, completionHandler);
                    }
                    return generateBotResponseCore(traceId, userMessage, chunkHandler, completionHandler, connectionId, slugName, user);
                });
    }

    private Uni<Void> generateBotResponseCore(UUID traceId, String userMessage, Consumer<String> chunkHandler, Consumer<String> completionHandler, String connectionId, String slugName, IUser user) {
        BrandStaticData cached = brandStaticCache.get(slugName);
        Uni<BrandStaticData> staticDataUni = cached != null ? Uni.createFrom().item(cached) : buildBrandStaticData(slugName);

        return staticDataUni.flatMap(staticData -> resolveUserLabel(user).flatMap(userLabel -> {
            String stationStatus = scenePool.getActiveScene(slugName) != null ? "online" : "offline";
            boolean authenticated = user.getEmail() != null && !user.getEmail().isBlank();
            // Keep the full template with {{isAuthenticated}} unresolved and the gate intact.
            // ChatAgent renders the auth slice per iteration from state.userId(), so the prompt
            // stays consistent with the tool set after an in-conversation sign-in.
            final String renderedPrompt = staticData.partialPrompt()
                    .replace("{{radioStationStatus}}", stationStatus)
                    .replace("{{userName}}", userLabel != null ? userLabel : "");

            ChatLogger.request(slugName, user.getId(), user.getEmail(),
                    authenticated, stationStatus);

            assistantNameByConnectionId.put(connectionId, staticData.djName());
            assistantNameByConnectionId.put(connectionId + "_voice", staticData.djPrimaryVoices());
            assistantNameByConnectionId.put(connectionId + "_lang", staticData.djLanguages());

            return loadConversationHistoryWithSummary(user.getId(), connectionId, slugName)
                    .flatMap(history -> runChatLoop(
                            traceId, staticData, renderedPrompt, history, stationStatus, user,
                            chunkHandler, completionHandler, connectionId, slugName));
        })).ifNoItem().after(java.time.Duration.ofSeconds(90)).fail()
        .onFailure().recoverWithUni(err -> {
            LOGGER.errorf("generateBotResponse failed or timed out for connectionId=%s: %s", connectionId, err.getMessage());
            chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
            completionHandler.accept(ChatMessageDTO.error("Something went wrong, please try again.", "system", connectionId).build().toJson());
            return Uni.createFrom().voidItem();
        }).runSubscriptionOn(getDefaultWorkerPool());
    }

    protected Uni<Void> runChatLoop(
            UUID traceId, BrandStaticData staticData, String renderedPrompt, List<LlmMessage> history,
            String stationStatus, IUser user, Consumer<String> chunkHandler,
            Consumer<String> completionHandler, String connectionId, String slugName) {
        Map<String, Object> initData = new java.util.HashMap<>();
        initData.put(ChatState.USER_ID, user.getId());
        initData.put(ChatState.CONNECTION_ID, connectionId);
        initData.put(ChatState.BRAND_NAME, slugName);
        initData.put(ChatState.HISTORY, new java.util.ArrayList<>(history));
        initData.put(ChatState.SYSTEM_PROMPT, renderedPrompt);
        initData.put(ChatState.DJ_NAME, staticData.djName());
        initData.put(ChatState.DJ_LANGUAGES, staticData.djLanguages());
        initData.put(ChatState.AD_ENABLED, staticData.adEnabled());
        initData.put(ChatState.ITERATION, 0);

        int startHistorySize = history.size();
        long startTs = System.currentTimeMillis();

        return chatAgent.run(initData).flatMap(finalState -> {
            long finalUserId = finalState.userId();
            String botText = finalState.botResponse();
            publishChatMetrics(traceId, slugName, finalUserId, stationStatus, finalState.history(), startHistorySize, botText, startTs);
            if (botText == null || botText.isBlank()) {
                if (finalUserId != 0 && user.getId() == 0) {
                    LOGGER.warnf("[ChatAgent] auth succeeded but LLM silent — sending fallback welcome userId=%d connectionId=%s", finalUserId, connectionId);
                    return emitPrecomputedResponse("You're all set! Welcome to " + slugName + ". What would you like to do?",
                            chunkHandler, completionHandler, connectionId, slugName, finalUserId)
                            .invoke(() -> sendDeferredSessionToken(finalState, connectionId));
                }
                LOGGER.warnf("[ChatAgent] empty botResponse userId=%d connectionId=%s", finalUserId, connectionId);
                chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                completionHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                return Uni.createFrom().voidItem();
            }
            String responseText = botText.replaceAll("(?s)<thinking>.*?</thinking>", "").trim();
            if (responseText.isBlank()) {
                chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                completionHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                return Uni.createFrom().voidItem();
            }
            chatRepository.replaceConversationHistory(
                    ChatRepository.connectionKey(connectionId),
                    finalState.history());
            return emitPrecomputedResponse(responseText, chunkHandler, completionHandler, connectionId, slugName, finalUserId)
                    .invoke(() -> sendDeferredSessionToken(finalState, connectionId));
        });
    }

    private void publishChatMetrics(UUID traceId, String brand, long userId, String stationStatus,
                                    List<LlmMessage> agentHistory, int startHistorySize,
                                    String botText, long startTs) {
        try {
            LlmMessage pendingToolUse = null;
            for (int i = startHistorySize; i < agentHistory.size(); i++) {
                LlmMessage msg = agentHistory.get(i);
                if (msg.kind() == LlmMessage.Kind.TOOL_USE) {
                    pendingToolUse = msg;
                } else if (msg.kind() == LlmMessage.Kind.TOOL_RESULT && pendingToolUse != null) {
                    metricPublisher.publishMetric(brand, MetricEventType.DEBUG, ProcessType.FLOW,
                            "chat_tool", Map.of(
                                    "tool", pendingToolUse.toolCall().name(),
                                    "input", pendingToolUse.toolCall().input().toString(),
                                    "output", msg.toolResultContent() != null ? msg.toolResultContent() : ""),
                            traceId);
                    pendingToolUse = null;
                }
            }
            if (botText != null && !botText.isBlank()) {
                String responseText = botText.replaceAll("(?s)<thinking>.*?</thinking>", "").trim();
                metricPublisher.publishMetric(brand, MetricEventType.DEBUG, ProcessType.FLOW,
                        "chat_bot", Map.of(
                                "response", responseText,
                                "userId", userId,
                                "status", stationStatus,
                                "durationMs", System.currentTimeMillis() - startTs),
                        traceId);
            }
        } catch (Exception e) {
            LOGGER.warnf("[ChatMetrics] failed to publish brand=%s: %s", brand, e.getMessage());
        }
    }

    protected Uni<BrandStaticData> buildBrandStaticData(String slugName) {
        return brandService.getBySlugName(slugName).flatMap(station -> {
            String radioStationName = station != null && station.getLocalizedName() != null
                    ? station.getLocalizedName().getOrDefault(LanguageCode.en, station.getSlugName()) : slugName;
            Uni<com.semantyca.mixpla.model.aiagent.AiAgent> agentUni = station != null && station.getAiAgentId() != null
                    ? aiAgentService.getById(station.getAiAgentId())
                    : Uni.createFrom().item(() -> null);
            return Uni.combine().all().unis(agentUni, otsScriptsProvider.buildScriptsText()).asTuple()
                    .map(tuple -> {
                        com.semantyca.mixpla.model.aiagent.AiAgent agent = tuple.getItem1();
                        String otsScripts = tuple.getItem2();
                        assert station != null;
                        boolean classifiedAdsEnabled = !Boolean.FALSE.equals(station.getChatFeatureFlags().get("CREATE_AD"));
                        boolean storePromoEnabled = Boolean.TRUE.equals(station.getChatFeatureFlags().get("STORE_PROMO"));
                        boolean adEnabled = classifiedAdsEnabled || storePromoEnabled;
                        String djName = agent.getName();
                        String djLanguages = agent.getPreferredLang().stream()
                                .sorted(java.util.Comparator.comparingDouble(com.semantyca.mixpla.model.aiagent.LanguagePreference::getWeight).reversed())
                                .map(lp -> lp.getLanguageTag().tag()).reduce((a, b) -> a + "," + b).orElse("");
                        String partialPrompt = getMainPrompt()
                                .replace("{{djName}}", sanitizePromptValue(djName))
                                .replace("{{radioStationName}}", sanitizePromptValue(radioStationName))
                                .replace("{{radioStationSlug}}", sanitizePromptValue(station.getSlugName()))
                                .replace("{{radioStationCountry}}", sanitizePromptValue(station.getCountry().getCountryName()))
                                .replace("{{radioStationTimeZone}}", sanitizePromptValue(station.getTimeZone().getId()))
                                .replace("{{radioStationDescription}}", sanitizePromptValue(station.getDescription()))
                                .replace("{{djLanguages}}", sanitizePromptValue(djLanguages))
                                .replace("{{djCopilotName}}", "")
                                .replace("{{musicMetadata}}", sanitizePromptValue(aiHelperService.getCachedMusicMetadata()))
                                .replace("{{otsScripts}}", sanitizePromptValue(otsScripts))
                                .replace("{{submissionPolicy}}", station.getSubmissionPolicy().name())
                                .replace("{{adEnabled}}", Boolean.toString(adEnabled));
                        BrandStaticData data = new BrandStaticData(djName, agent.getTtsSetting().getDj().getId(), djLanguages, partialPrompt, adEnabled);
                        brandStaticCache.put(slugName, data);
                        return data;
                    });
        });
    }

    protected Uni<String> resolveUserLabel(IUser user) {
        if (user.getId() == 0) return Uni.createFrom().item("");
        return listenerService.resolveDisplayName(user.getId(), null);
    }

    protected Uni<Void> emitPrecomputedResponse(
            String precomputedText,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {
        return Uni.createFrom().item(() -> {
            String responseText = precomputedText
                    .replaceAll("(?s)<thinking>.*?</thinking>", "")
                    .trim();
            if (responseText.isEmpty()) {
                LOGGER.warnf("[emitPrecomputed] empty text userId=%d connectionId=%s", userId, connectionId);
                chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                completionHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                return null;
            }
            String djName = assistantNameByConnectionId.get(connectionId);
            chunkHandler.accept(ChatMessageDTO.chunk(responseText, djName, connectionId).build().toJson());
            chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());

            chatRepository.appendToConversation(
                    ChatRepository.connectionKey(connectionId),
                    LlmMessage.text(LlmMessage.Role.ASSISTANT, responseText));

            ChatMessageEnvelope botMessage = ChatMessageEnvelope.of(MessageType.BOT, djName, responseText, System.currentTimeMillis(), connectionId);

            chatRepository.saveChatMessage(userId, brandName, ChatType.PUBLIC, botMessage).subscribe().with(
                    success -> {},
                    failure -> LOGGER.error("Failed to save bot message", failure)
            );

            String completeMessage = ChatMessageDTO.bot(botMessage.content(), botMessage.username(), botMessage.connectionId())
                    .timestamp(botMessage.timestamp())
                    .build()
                    .toJson();
            completionHandler.accept(completeMessage);
            return null;
        }).replaceWithVoid().runSubscriptionOn(getDefaultWorkerPool());
    }

    protected Uni<List<LlmMessage>> loadConversationHistoryWithSummary(long userId, String connectionId, String brandName) {
        List<LlmMessage> rawHistory = chatRepository.getConversationHistory(ChatRepository.connectionKey(connectionId));
        List<LlmMessage> currentHistory = trimOrphanedUserMessages(rawHistory);

        return chatSummaryService.getLatestUserSummary(userId, brandName, ChatType.PUBLIC)
                .map(summaryText -> {
                    if (summaryText != null && !summaryText.isBlank() && currentHistory.size() > 10) {
                        List<LlmMessage> historyWithSummary = new ArrayList<>();
                        historyWithSummary.add(LlmMessage.text(LlmMessage.Role.USER,
                                "[Previous conversation summary]\n" + summaryText + "\n[End of summary]"));
                        int recentMessagesStart = Math.max(0, currentHistory.size() - 10);
                        historyWithSummary.addAll(currentHistory.subList(recentMessagesStart, currentHistory.size()));
                        return historyWithSummary;
                    }
                    return currentHistory;
                });
    }

    private static List<LlmMessage> trimOrphanedUserMessages(List<LlmMessage> history) {
        if (history.isEmpty()) return history;
        int lastIndex = history.size() - 1;
        LlmMessage last = history.get(lastIndex);
        if (last.kind() != LlmMessage.Kind.TEXT || last.role() != LlmMessage.Role.USER) {
            return history;
        }
        // Remove consecutive orphaned USER TEXT messages before the last one
        int end = lastIndex;
        while (end > 0
                && history.get(end - 1).kind() == LlmMessage.Kind.TEXT
                && history.get(end - 1).role() == LlmMessage.Role.USER) {
            end--;
        }
        if (end == lastIndex) return history;
        List<LlmMessage> result = new ArrayList<>(history.subList(0, end));
        result.add(last);
        return result;
    }

    public void clearConversationHistory(String connectionId, long userId) {
        chatRepository.clearConversationHistory(ChatRepository.connectionKey(connectionId));
        if (userId != 0) {
            chatRepository.clearConversationHistory(ChatRepository.userKey(userId));
        }
    }

    protected static String sanitizePromptValue(String value) {
        if (value == null) return "";
        return value
                .replaceAll("\\{\\{.*?}}", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                .trim();
    }

    private void sendDeferredSessionToken(ChatState finalState, String connectionId) {
        String token = finalState.sessionToken();
        if (token != null) {
            long userId = finalState.userId();
            LOGGER.infof("[ChatAgent] sending deferred session_token userId=%d connectionId=%s", userId, connectionId);
            listenerService.resolveDisplayName(userId, finalState.sessionUserName()).subscribe().with(
                    displayName -> {
                        controller.sendToConnection(connectionId, new io.vertx.core.json.JsonObject()
                                .put("type", "session_token")
                                .put("token", token)
                                .put("userName", displayName)
                                .encode());
                        if (userId > 0) {
                            userService.findById(userId).subscribe().with(
                                    opt -> opt.ifPresent(user -> controller.upgradeUserSession(connectionId, user)),
                                    err -> LOGGER.warnf("[sendDeferredSessionToken] failed to upgrade session for userId=%d conn=%s: %s", userId, connectionId, err.getMessage())
                            );
                        }
                    },
                    err -> LOGGER.warnf("[sendDeferredSessionToken] failed to resolve display name for userId=%d conn=%s: %s", userId, connectionId, err.getMessage())
            );
        }
    }

    public void bootstrapConnectionHistory(String connectionId, long userId) {
        chatRepository.bootstrapConnectionFromUser(connectionId, userId);
    }

    public void persistConnectionHistory(String connectionId, long userId) {
        chatRepository.persistConnectionToUser(connectionId, userId);
    }

    protected String getMainPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/mainPrompt.hbs");
            return !custom.isBlank() ? custom : this.mainPrompt;
        } catch (Exception ignored) {
            return this.mainPrompt;
        }
    }

}
