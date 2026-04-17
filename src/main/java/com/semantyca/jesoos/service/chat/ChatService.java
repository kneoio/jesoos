package com.semantyca.jesoos.service.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.*;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.MessageType;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.ScenePool;
import com.semantyca.jesoos.service.live.scripting.PerplexitySearchHelper;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import com.semantyca.mixpla.model.brand.Brand;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

public abstract class ChatService {
    private static final Logger LOGGER = Logger.getLogger(ChatService.class);
    
    protected final AnthropicClient anthropicClient;
    protected final AiHelperService aiHelperService;
    protected final String mainPrompt;
    protected final String followUpPrompt;
    protected final JesoosConfig config;
    protected final ConcurrentHashMap<String, String> assistantNameByConnectionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BrandStaticData> brandStaticCache = new ConcurrentHashMap<>();

    private record BrandStaticData(String djName, String djPrimaryVoices, String partialPrompt) {}

    @Inject
    protected ScenePool scenePool;
    @Inject
    protected BrandService brandService;
    @Inject
    protected AiAgentService aiAgentService;
    @Inject
    protected ChatRepository chatRepository;
    @Inject
    protected PerplexitySearchHelper perplexitySearchHelper;
    @Inject
    protected ChatSummaryService chatSummaryService;

    protected ChatService(JesoosConfig config, AiHelperService aiHelperService) {
        if (config != null) {
            this.anthropicClient = AnthropicOkHttpClient.builder()
                    .apiKey(config.getAnthropicApiKey())
                    .build();
            this.aiHelperService = aiHelperService;
            this.config = config;
            this.mainPrompt = loadPromptTemplate("prompts/mainPrompt.hbs");
            this.followUpPrompt = loadPromptTemplate("prompts/followUpPrompt.hbs");
        } else {
            this.anthropicClient = null;
            this.aiHelperService = null;
            this.config = null;
            this.mainPrompt = null;
            this.followUpPrompt = null;
        }
    }

    @Deprecated
    private String loadPromptTemplate(String resourcePath) {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load resource: " + resourcePath, e);
        }
    }

    protected String getMainPrompt() {
        return this.mainPrompt;
    }

    protected String getFollowUpPrompt() {
        return this.followUpPrompt;
    }

    protected abstract ChatType getChatType();

    public Uni<Void> migrateAnonymousSession(String connectionId, long newUserId) {
        return chatRepository.migrateAnonymousSession(connectionId, newUserId, getChatType());
    }

    public Uni<String> processUserMessage(String username, String content, String connectionId, String brandName, IUser user) {
        return Uni.createFrom().item(() -> {
            JsonObject message = createMessage(
                    MessageType.USER,
                    username,
                    content,
                    System.currentTimeMillis(),
                    connectionId
            );

            chatRepository.saveChatMessage(user.getId(), brandName, getChatType(), message).subscribe().with(
                    success -> {},
                    failure -> LOGGER.error("Failed to save user message", failure)
            );

            String sessionKey = ChatRepository.sessionKey(user.getId(), connectionId, getChatType());
            MessageParam userMsg = MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofString(content))
                    .build();
            chatRepository.appendToConversation(sessionKey, userMsg);

            return ChatMessageDTO.user(content, username, connectionId).build().toJson();
        });
    }

    public Uni<String> getChatHistory(String brandName, int limit, String connectionId, IUser user) {
        return chatRepository.getRecentChatMessages(user.getId(), connectionId, brandName, getChatType(), limit)
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

        BrandStaticData cached = brandStaticCache.get(slugName);
        Uni<BrandStaticData> staticDataUni = cached != null
                ? Uni.createFrom().item(cached)
                : brandService.getBySlugName(slugName).flatMap(station -> {
                    String radioStationName = station != null && station.getLocalizedName() != null
                            ? station.getLocalizedName().getOrDefault(LanguageCode.en, station.getSlugName())
                            : slugName;
                    Uni<AiAgent> agentUni = station != null && station.getAiAgentId() != null
                            ? aiAgentService.getById(station.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            : Uni.createFrom().item(() -> null);
                    return agentUni.map(agent -> {
                        assert station != null;
                        String djName = agent.getName();
                        String stationSlug = station.getSlugName();
                        String djLanguages = agent.getPreferredLang().stream()
                                .sorted(java.util.Comparator.comparingDouble(LanguagePreference::getWeight).reversed())
                                .map(lp -> lp.getLanguageTag().name())
                                .reduce((a, b) -> a + "," + b).orElse("");
                        String partialPrompt = getMainPrompt()
                                .replace("{{djName}}", djName)
                                .replace("{{radioStationName}}", radioStationName)
                                .replace("{{radioStationSlug}}", stationSlug)
                                .replace("{{radioStationCountry}}", station.getCountry().getCountryName())
                                        .replace("{{radioStationTimeZone}}", station.getTimeZone().getId())
                                .replace("{{radioStationDescription}}", station.getDescription())
                                .replace("{{djLanguages}}", djLanguages)
                                .replace("{{djCopilotName}}", "");
                        BrandStaticData data = new BrandStaticData(djName, agent.getTtsSetting().getDj().getId(), partialPrompt);
                        brandStaticCache.put(slugName, data);
                        return data;
                    });
                });

        return staticDataUni.flatMap(staticData -> {
                String stationStatus = scenePool.getActiveScene(slugName) != null ? "online" : "offline";
                String isAuthenticated = Boolean.toString(user.getEmail() != null && !user.getEmail().isBlank());
                String renderedPrompt = staticData.partialPrompt()
                        .replace("{{radioStationStatus}}", stationStatus)
                        .replace("{{userName}}", user.getEmail() != null && !user.getEmail().isBlank() ? user.getEmail() : user.getUserName())
                        .replace("{{isAuthenticated}}", isAuthenticated);

                ChatLogger.request(slugName, user.getId(), user.getEmail(),
                        Boolean.parseBoolean(isAuthenticated), stationStatus);

                assistantNameByConnectionId.put(connectionId, staticData.djName());
                assistantNameByConnectionId.put(connectionId + "_voice", staticData.djPrimaryVoices());

                return loadConversationHistoryWithSummary(user.getId(), connectionId, slugName, getChatType())
                        .<MessageCreateParams>map(history -> buildMessageCreateParams(renderedPrompt, history, user));
        }).flatMap(params ->
                Uni.createFrom().completionStage(() -> anthropicClient.async().messages().create(params))
                        .flatMap(message -> {
                            Optional<ToolUseBlock> toolUse = message.content().stream()
                                    .flatMap(block -> block.toolUse().stream())
                                    .findFirst();

                            if (toolUse.isPresent()) {
                                ChatLogger.firstCall(toolUse.get().name());
                                List<MessageParam> history = chatRepository.getConversationHistory(ChatRepository.sessionKey(user.getId(), connectionId, getChatType()));
                                return handleToolCall(toolUse.get(), chunkHandler, completionHandler, connectionId, slugName, user.getId(), history);
                            } else {
                                ChatLogger.firstCallNoTool();
                                return streamResponse(params, chunkHandler, completionHandler, connectionId, slugName, user.getId());
                            }
                        })
        ).runSubscriptionOn(getDefaultWorkerPool());
    }

    protected abstract MessageCreateParams buildMessageCreateParams(String renderedPrompt, List<MessageParam> history, IUser user);

    protected abstract List<Tool> getAvailableTools();

    protected abstract Uni<Void> handleToolCall(ToolUseBlock toolUse,
                                                Consumer<String> chunkHandler,
                                                Consumer<String> completionHandler,
                                                String connectionId,
                                                String brandName,
                                                long userId,
                                                List<MessageParam> conversationHistory);

    protected Uni<Void> streamResponse(MessageCreateParams params,
                                     Consumer<String> chunkHandler,
                                     Consumer<String> completionHandler,
                                     String connectionId,
                                     String brandName,
                                     long userId) {

        return Uni.createFrom().completionStage(() -> {

            StringBuilder fullResponse = new StringBuilder();
            boolean[] inThinking = {false};

            return anthropicClient.async().messages().createStreaming(params)
                    .subscribe(new AsyncStreamResponse.Handler<>() {

                        @Override
                        public void onNext(RawMessageStreamEvent chunk) {
                            try {
                                if (chunk.contentBlockDelta().isPresent()) {
                                    RawContentBlockDelta delta = chunk.contentBlockDelta().get().delta();

                                    if (delta.text().isPresent()) {
                                        String text = delta.text().get().text();
                                        fullResponse.append(text);

                                        if (text.contains("<thinking>")) {
                                            inThinking[0] = true;
                                        }
                                        if (text.contains("</thinking>")) {
                                            inThinking[0] = false;
                                        }

                                        if (!inThinking[0]
                                                && !text.contains("<thinking>")
                                                && !text.contains("</thinking>")) {

                                            chunkHandler.accept(ChatMessageDTO.chunk(text, assistantNameByConnectionId.get(connectionId), connectionId).build().toJson());
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        @Override
                        public void onComplete(@NotNull Optional<Throwable> error) {

                            if (error.isPresent()) {
                                completionHandler.accept(ChatMessageDTO.error("Bot response failed: " + error.get().getMessage(), "system", "system").build().toJson());
                                return;
                            }

                            String responseText = fullResponse.toString()
                                    .replaceAll("(?s)<thinking>.*?</thinking>", "")
                                    .trim();

                            if (!responseText.isEmpty()) {

                                MessageParam assistantResponse = MessageParam.builder()
                                        .role(MessageParam.Role.ASSISTANT)
                                        .content(MessageParam.Content.ofString(responseText))
                                        .build();

                                chatRepository.appendToConversation(ChatRepository.sessionKey(userId, connectionId, getChatType()), assistantResponse);

                                JsonObject botMessage = createMessage(
                                        MessageType.BOT,
                                        assistantNameByConnectionId.get(connectionId),
                                        responseText,
                                        System.currentTimeMillis(),
                                        connectionId
                                );

                                chatRepository.saveChatMessage(userId, brandName, getChatType(), botMessage).subscribe().with(
                                        success -> {},
                                        failure -> LOGGER.error("Failed to save bot message", failure)
                                );


                                String completeMessage = ChatMessageDTO.bot(
                    botMessage.getJsonObject("data").getString("content"),
                    botMessage.getJsonObject("data").getString("username"),
                    botMessage.getJsonObject("data").getString("connectionId")
            )
            .timestamp(botMessage.getJsonObject("data").getLong("timestamp"))
            .build()
            .toJson();


                                completionHandler.accept(completeMessage);
                            }
                        }
                    })
                    .onCompleteFuture();

        }).runSubscriptionOn(getDefaultWorkerPool());
    }

    protected Uni<List<MessageParam>> loadConversationHistoryWithSummary(long userId, String connectionId, String brandName, ChatType chatType) {
        List<MessageParam> currentHistory = chatRepository.getConversationHistory(ChatRepository.sessionKey(userId, connectionId, chatType));
        
        return chatSummaryService.getLatestUserSummary(userId, brandName, chatType)
                .map(summaryText -> {
                    if (summaryText != null && !summaryText.isBlank() && currentHistory.size() > 10) {
                        List<MessageParam> historyWithSummary = new ArrayList<>();
                        
                        MessageParam summaryMessage = MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(MessageParam.Content.ofString(
                                        "[Previous conversation summary]\n" + summaryText + "\n[End of summary]"
                                ))
                                .build();
                        historyWithSummary.add(summaryMessage);
                        
                        int recentMessagesStart = Math.max(0, currentHistory.size() - 10);
                        historyWithSummary.addAll(currentHistory.subList(recentMessagesStart, currentHistory.size()));
                        
                        return historyWithSummary;
                    }
                    return currentHistory;
                });
    }

    protected JsonObject createMessage(MessageType type, String username, String content, long timestamp, String connectionId) {
        return new JsonObject()
                .put("type", "message")
                .put("data", new JsonObject()
                        .put("type", type.name())
                        .put("id", UUID.randomUUID().toString())
                        .put("username", username)
                        .put("content", content)
                        .put("timestamp", timestamp)
                        .put("connectionId", connectionId)
                );
    }

    protected Map<String, JsonValue> extractInputMap(ToolUseBlock toolUse) {
        JsonValue inputVal = toolUse._input();
        Optional<Map<String, JsonValue>> maybeObj = inputVal.asObject();
        return maybeObj.orElse(Collections.emptyMap());
    }

    protected Function<MessageCreateParams, Uni<Void>> createStreamFunction(
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {
        return params -> handleFollowUpWithToolDetection(params, chunkHandler, completionHandler, connectionId, brandName, userId);
    }

    protected Uni<Void> handleFollowUpWithToolDetection(
            MessageCreateParams params,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {
        
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(params.maxTokens())
                .system(Objects.requireNonNull(params.system().orElse(null)))
                .messages(params.messages())
                .model(params.model());
        
        for (Tool tool : getAvailableTools()) {
            builder.addTool(tool);
        }
        
        MessageCreateParams paramsWithTools = builder.build();
        
        return Uni.createFrom().completionStage(() -> anthropicClient.async().messages().create(paramsWithTools))
                .flatMap(message -> {
                    Optional<ToolUseBlock> toolUse = message.content().stream()
                            .flatMap(block -> block.toolUse().stream())
                            .findFirst();

                    if (toolUse.isPresent()) {
                        LOGGER.debugf("Follow-up detected tool call: %s", toolUse.get().name());
                        List<MessageParam> history = chatRepository.getConversationHistory(ChatRepository.sessionKey(userId, connectionId, getChatType()));
                        return handleToolCall(toolUse.get(), chunkHandler, completionHandler, connectionId, brandName, userId, history);
                    } else {
                        return streamResponse(params, chunkHandler, completionHandler, connectionId, brandName, userId);
                    }
                }).runSubscriptionOn(getDefaultWorkerPool());
    }
}
