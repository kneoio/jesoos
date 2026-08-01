package com.semantyca.jesoos.service.help;

import com.semantyca.core.model.cnst.MessageType;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.chat.ChatMessageEnvelope;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.ws.HelpChatController;
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

/**
 * Public interactive help about Mixpla. Anonymous only — every row is stored with user_id 0 and
 * keyed by connection, and nothing here can touch a listener, a brand or an account.
 */
@ApplicationScoped
public class HelpChatService {

    private static final Logger LOGGER = Logger.getLogger(HelpChatService.class);

    /** Fixed scope key stored in brand_name column; not a brand. */
    public static final String SCOPE_KEY = "mixpla";
    public static final String ASSISTANT_NAME = "Mixpla Help";
    public static final String ASSISTANT_LANGUAGES = "en";

    private static final long ANONYMOUS_USER_ID = 0L;

    private final String helpPrompt;

    @Inject ChatRepository chatRepository;
    @Inject HelpAgent helpAgent;
    @Inject MetricPublisher metricPublisher;

    @Setter
    private HelpChatController controller;

    public HelpChatService() {
        this.helpPrompt = loadHelpPrompt();
    }

    private static String loadHelpPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/helpPrompt.hbs");
            if (custom != null && !custom.isBlank()) return custom;
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return ResourceUtil.loadResourceAsString("prompts/helpPrompt.hbs");
        } catch (Exception e) {
            LOGGER.error("Failed to load helpPrompt.hbs", e);
            return "";
        }
    }

    public static String helpConnectionKey(String connectionId) {
        return "help_conn_" + connectionId;
    }

    public Uni<String> processUserMessage(String content, String connectionId) {
        return Uni.createFrom().item(() -> {
            ChatMessageEnvelope message = ChatMessageEnvelope.of(
                    MessageType.USER, "visitor", content, System.currentTimeMillis(), connectionId);

            chatRepository.saveChatMessage(ANONYMOUS_USER_ID, SCOPE_KEY, ChatType.HELP, message).subscribe().with(
                    success -> {},
                    failure -> LOGGER.error("Failed to save help user message", failure)
            );

            chatRepository.appendToConversation(
                    helpConnectionKey(connectionId),
                    LlmMessage.text(LlmMessage.Role.USER, content));

            return ChatMessageDTO.user(content, "visitor", connectionId).build().toJson();
        });
    }

    public Uni<String> getChatHistory(int limit, String connectionId) {
        return chatRepository.getRecentChatMessages(ANONYMOUS_USER_ID, connectionId, SCOPE_KEY, ChatType.HELP, limit)
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
            String connectionId) {
        UUID traceId = UUID.randomUUID();
        metricPublisher.publishMetric(SCOPE_KEY, MetricEventType.DEBUG, ProcessType.FLOW,
                "help_user", Map.of("message", userMessage, "connectionId", connectionId), traceId);

        String renderedPrompt = helpPrompt.replace("{{assistantLanguages}}", ASSISTANT_LANGUAGES);

        List<LlmMessage> history = new ArrayList<>(
                chatRepository.getConversationHistory(helpConnectionKey(connectionId)));

        Map<String, Object> initData = new HashMap<>();
        initData.put(HelpState.CONNECTION_ID, connectionId);
        initData.put(HelpState.HISTORY, history);
        initData.put(HelpState.SYSTEM_PROMPT, renderedPrompt);
        initData.put(HelpState.ASSISTANT_NAME, ASSISTANT_NAME);
        initData.put(HelpState.ITERATION, 0);

        long startTs = System.currentTimeMillis();

        return helpAgent.run(initData).flatMap(finalState -> {
            String botText = finalState.botResponse();
            if (botText != null && !botText.isBlank()) {
                metricPublisher.publishMetric(SCOPE_KEY, MetricEventType.DEBUG, ProcessType.FLOW,
                        "help_bot", Map.of(
                                "response", botText,
                                "durationMs", System.currentTimeMillis() - startTs),
                        traceId);
            }

            String responseText = botText == null ? "" : botText.replaceAll("(?s)<thinking>.*?</thinking>", "").trim();
            if (responseText.isBlank()) {
                chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                completionHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                return Uni.createFrom().voidItem();
            }

            chatRepository.replaceConversationHistory(
                    helpConnectionKey(connectionId),
                    finalState.history());

            return emitResponse(responseText, chunkHandler, completionHandler, connectionId,
                    finalState.responseStreamed());
        }).ifNoItem().after(java.time.Duration.ofSeconds(90)).fail()
        .onFailure().recoverWithUni(err -> {
            LOGGER.errorf("Help generateBotResponse failed connectionId=%s: %s", connectionId, err.getMessage());
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
            boolean alreadyStreamed) {
        return Uni.createFrom().item(() -> {
            if (!alreadyStreamed) {
                chunkHandler.accept(ChatMessageDTO.chunk(text, ASSISTANT_NAME, connectionId).build().toJson());
            }
            chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());

            chatRepository.appendToConversation(
                    helpConnectionKey(connectionId),
                    LlmMessage.text(LlmMessage.Role.ASSISTANT, text));

            ChatMessageEnvelope botMessage = ChatMessageEnvelope.of(
                    MessageType.BOT, ASSISTANT_NAME, text, System.currentTimeMillis(), connectionId);
            chatRepository.saveChatMessage(ANONYMOUS_USER_ID, SCOPE_KEY, ChatType.HELP, botMessage).subscribe().with(
                    success -> {},
                    failure -> LOGGER.error("Failed to save help bot message", failure)
            );

            completionHandler.accept(ChatMessageDTO.bot(text, ASSISTANT_NAME, connectionId)
                    .timestamp(botMessage.timestamp()).build().toJson());
            return null;
        }).replaceWithVoid().runSubscriptionOn(getDefaultWorkerPool());
    }

    public void dropConversation(String connectionId) {
        chatRepository.clearConversationHistory(helpConnectionKey(connectionId));
    }
}
