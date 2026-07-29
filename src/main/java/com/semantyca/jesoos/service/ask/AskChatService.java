package com.semantyca.jesoos.service.ask;

import com.semantyca.core.model.cnst.MessageType;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.chat.ChatMessageEnvelope;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.ws.AskChatController;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Setter;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class AskChatService {

    private static final Logger LOGGER = Logger.getLogger(AskChatService.class);

    /** Fixed scope key stored in brand_name column; not a brand. */
    public static final String SCOPE_KEY = "mixpla";
    public static final String ASSISTANT_NAME = "Mixplaclone";
    public static final String ASSISTANT_LANGUAGES = "en";

    private final String askPrompt;

    @Inject ChatRepository chatRepository;
    @Inject AskAgent askAgent;
    @Inject MetricPublisher metricPublisher;

    @Setter
    private AskChatController controller;

    public AskChatService() {
        this.askPrompt = loadAskPrompt();
    }

    private static String loadAskPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/askPrompt.hbs");
            if (custom != null && !custom.isBlank()) return custom;
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return ResourceUtil.loadResourceAsString("prompts/askPrompt.hbs");
        } catch (Exception e) {
            LOGGER.error("Failed to load askPrompt.hbs", e);
            return "";
        }
    }

    public static String askConnectionKey(String connectionId) {
        return "ask_conn_" + connectionId;
    }

    public static String askUserKey(long userId) {
        return "ask_user_" + userId;
    }

    public Uni<String> processUserMessage(String username, String content, String connectionId, IUser user) {
        return Uni.createFrom().item(() -> {
            ChatMessageEnvelope message = ChatMessageEnvelope.of(
                    MessageType.USER, username, content, System.currentTimeMillis(), connectionId);

            chatRepository.saveChatMessage(user.getId(), SCOPE_KEY, ChatType.ASK, message).subscribe().with(
                    success -> {},
                    failure -> LOGGER.error("Failed to save ask user message", failure)
            );

            chatRepository.appendToConversation(
                    askConnectionKey(connectionId),
                    LlmMessage.text(LlmMessage.Role.USER, content));

            return ChatMessageDTO.user(content, username, connectionId).build().toJson();
        });
    }

    public Uni<String> getChatHistory(int limit, String connectionId, IUser user) {
        return chatRepository.getRecentChatMessages(user.getId(), connectionId, SCOPE_KEY, ChatType.ASK, limit)
                .map(recentMessages -> {
                    JsonArray messages = new JsonArray();
                    recentMessages.forEach(messages::add);
                    return new JsonObject().put("type", "history").put("messages", messages).encode();
                });
    }

    public Uni<Void> generateBotResponse(
            String userMessage,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            IUser user) {
        UUID traceId = UUID.randomUUID();
        metricPublisher.publishMetric(SCOPE_KEY, MetricEventType.DEBUG, ProcessType.FLOW,
                "ask_user", Map.of("message", userMessage, "userId", user.getId(), "connectionId", connectionId), traceId);

        String userLabel = user.getId() != 0 && user.getLogin() != null ? user.getLogin() : "";
        String renderedPrompt = askPrompt
                .replace("{{assistantLanguages}}", ASSISTANT_LANGUAGES)
                .replace("{{userName}}", sanitize(userLabel));
        // {{isAuthenticated}} left for AskAgent per iteration

        List<LlmMessage> history = trimOrphanedUserMessages(
                chatRepository.getConversationHistory(askConnectionKey(connectionId)));

        Map<String, Object> initData = new HashMap<>();
        initData.put(AskState.USER_ID, user.getId());
        initData.put(AskState.CONNECTION_ID, connectionId);
        initData.put(AskState.HISTORY, new ArrayList<>(history));
        initData.put(AskState.SYSTEM_PROMPT, renderedPrompt);
        initData.put(AskState.ASSISTANT_NAME, ASSISTANT_NAME);
        initData.put(AskState.ITERATION, 0);

        int startHistorySize = history.size();
        long startTs = System.currentTimeMillis();

        return askAgent.run(initData).flatMap(finalState -> {
            long finalUserId = finalState.userId();
            String botText = finalState.botResponse();
            publishMetrics(traceId, finalUserId, finalState.history(), startHistorySize, botText, startTs);

            if (botText == null || botText.isBlank()) {
                if (finalUserId != 0 && user.getId() == 0) {
                    return emitResponse("You're signed in. What would you like to know about Mixpla?",
                            chunkHandler, completionHandler, connectionId, finalUserId, false)
                            .invoke(() -> sendDeferredSessionToken(finalState, connectionId));
                }
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
                    askConnectionKey(connectionId),
                    finalState.history());

            return emitResponse(responseText, chunkHandler, completionHandler, connectionId, finalUserId,
                    finalState.responseStreamed())
                    .invoke(() -> sendDeferredSessionToken(finalState, connectionId));
        }).ifNoItem().after(java.time.Duration.ofSeconds(90)).fail()
        .onFailure().recoverWithUni(err -> {
            LOGGER.errorf("Ask generateBotResponse failed connectionId=%s: %s", connectionId, err.getMessage());
            chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
            completionHandler.accept(ChatMessageDTO.error("Something went wrong, please try again.", "system", connectionId).build().toJson());
            return Uni.createFrom().voidItem();
        }).runSubscriptionOn(getDefaultWorkerPool());
    }

    private Uni<Void> emitResponse(
            String text,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            long userId,
            boolean alreadyStreamed) {
        return Uni.createFrom().item(() -> {
            if (!alreadyStreamed) {
                chunkHandler.accept(ChatMessageDTO.chunk(text, ASSISTANT_NAME, connectionId).build().toJson());
            }
            chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());

            chatRepository.appendToConversation(
                    askConnectionKey(connectionId),
                    LlmMessage.text(LlmMessage.Role.ASSISTANT, text));

            ChatMessageEnvelope botMessage = ChatMessageEnvelope.of(
                    MessageType.BOT, ASSISTANT_NAME, text, System.currentTimeMillis(), connectionId);
            chatRepository.saveChatMessage(userId, SCOPE_KEY, ChatType.ASK, botMessage).subscribe().with(
                    success -> {},
                    failure -> LOGGER.error("Failed to save ask bot message", failure)
            );

            completionHandler.accept(ChatMessageDTO.bot(text, ASSISTANT_NAME, connectionId)
                    .timestamp(botMessage.timestamp()).build().toJson());
            return null;
        }).replaceWithVoid().runSubscriptionOn(getDefaultWorkerPool());
    }

    private void sendDeferredSessionToken(AskState finalState, String connectionId) {
        String token = finalState.sessionToken();
        if (token == null || controller == null) return;
        long userId = finalState.userId();
        String userName = finalState.sessionUserName() != null ? finalState.sessionUserName() : "";
        controller.sendToConnection(connectionId, new JsonObject()
                .put("type", "session_token")
                .put("token", token)
                .put("userName", userName)
                .encode());
        if (userId > 0) {
            // Session already upgraded in tool node; token notify is for the client.
            LOGGER.infof("[AskChat] deferred session_token userId=%d connectionId=%s", userId, connectionId);
        }
    }

    private void publishMetrics(UUID traceId, long userId, List<LlmMessage> agentHistory,
                                int startHistorySize, String botText, long startTs) {
        try {
            LlmMessage pendingToolUse = null;
            for (int i = startHistorySize; i < agentHistory.size(); i++) {
                LlmMessage msg = agentHistory.get(i);
                if (msg.kind() == LlmMessage.Kind.TOOL_USE) {
                    pendingToolUse = msg;
                } else if (msg.kind() == LlmMessage.Kind.TOOL_RESULT && pendingToolUse != null) {
                    metricPublisher.publishMetric(SCOPE_KEY, MetricEventType.DEBUG, ProcessType.FLOW,
                            "ask_tool", Map.of(
                                    "tool", pendingToolUse.toolCall().name(),
                                    "input", pendingToolUse.toolCall().input().toString(),
                                    "output", msg.toolResultContent() != null ? msg.toolResultContent() : ""),
                            traceId);
                    pendingToolUse = null;
                }
            }
            if (botText != null && !botText.isBlank()) {
                metricPublisher.publishMetric(SCOPE_KEY, MetricEventType.DEBUG, ProcessType.FLOW,
                        "ask_bot", Map.of(
                                "response", botText.replaceAll("(?s)<thinking>.*?</thinking>", "").trim(),
                                "userId", userId,
                                "durationMs", System.currentTimeMillis() - startTs),
                        traceId);
            }
        } catch (Exception e) {
            LOGGER.warnf("[AskMetrics] failed: %s", e.getMessage());
        }
    }

    public void bootstrapConnectionHistory(String connectionId, long userId) {
        if (userId == 0) return;
        String connKey = askConnectionKey(connectionId);
        List<LlmMessage> existing = chatRepository.getConversationHistory(connKey);
        if (!existing.isEmpty()) return;
        List<LlmMessage> userHistory = chatRepository.getConversationHistory(askUserKey(userId));
        if (!userHistory.isEmpty()) {
            chatRepository.replaceConversationHistory(connKey, userHistory);
        }
    }

    public void persistConnectionHistory(String connectionId, long userId) {
        if (userId == 0) return;
        List<LlmMessage> connHistory = chatRepository.getConversationHistory(askConnectionKey(connectionId));
        if (!connHistory.isEmpty()) {
            chatRepository.replaceConversationHistory(askUserKey(userId), connHistory);
        }
    }

    public void clearConversationHistory(String connectionId, long userId) {
        chatRepository.clearConversationHistory(askConnectionKey(connectionId));
        if (userId != 0) {
            chatRepository.clearConversationHistory(askUserKey(userId));
        }
    }

    private static List<LlmMessage> trimOrphanedUserMessages(List<LlmMessage> history) {
        if (history.isEmpty()) return history;
        int lastIndex = history.size() - 1;
        LlmMessage last = history.get(lastIndex);
        if (last.kind() != LlmMessage.Kind.TEXT || last.role() != LlmMessage.Role.USER) {
            return history;
        }
        int end = lastIndex;
        while (end > 0
                && history.get(end - 1).kind() == LlmMessage.Kind.TEXT
                && history.get(end - 1).role() == LlmMessage.Role.USER) {
            end--;
        }
        if (end == lastIndex) return history;
        List<LlmMessage> trimmed = new ArrayList<>(history.subList(0, end));
        trimmed.add(last);
        return trimmed;
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return value.replace("{{", "").replace("}}", "");
    }
}
