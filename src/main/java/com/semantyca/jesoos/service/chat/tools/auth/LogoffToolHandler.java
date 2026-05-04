package com.semantyca.jesoos.service.chat.tools.auth;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.chat.ChatService;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.chat.tools.BaseToolHandler;
import com.semantyca.jesoos.ws.PublicChatController;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class LogoffToolHandler extends BaseToolHandler {

    private static final Logger LOG = Logger.getLogger(LogoffToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            PublicChatSessionManager sessionManager,
            UserService userService,
            PublicChatController controller,
            ChatService chatService,
            MetricPublisher metricPublisher,
            String brandName,
            long userId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        LogoffToolHandler handler = new LogoffToolHandler();

        return userService.findById(userId)
                .onItem().transformToUni(opt -> {
                    if (opt.isEmpty() || opt.get().getEmail() == null) {
                        return handleError(toolCall, "No active session found", handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                    }

                    String email = opt.get().getEmail();
                    LOG.infof("[Logoff] Logging out user: %s", email);

                    return sessionManager.deleteTokenByEmail(email)
                            .onItem().transformToUni(v -> {
                                controller.downgradeUserSession(connectionId);
                                chatService.clearConversationHistory(connectionId, userId);
                                metricPublisher.publishMetric(brandName, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                        "logoff", Map.of("email", email, "userId", userId, "connectionId", connectionId));
                                controller.sendToConnection(connectionId, new JsonObject()
                                        .put("type", "session_token").put("token", (Object) null).encode());

                                JsonObject payload = new JsonObject().put("ok", true).put("message", "Logged out successfully");
                                handler.addToolUseToHistory(toolCall, conversationHistory);
                                handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                                return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                            });
                })
                .onFailure().recoverWithUni(err -> {
                    LOG.error("[Logoff] Failed for userId: {}", userId, err);
                    return handleError(toolCall, "Logoff failed: " + err.getMessage(), handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(LlmToolCall toolCall, String errorMessage, LogoffToolHandler handler,
                                         Consumer<String> chunkHandler, String connectionId,
                                         List<LlmMessage> conversationHistory, String systemPromptCall2,
                                         Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject errorPayload = new JsonObject().put("ok", false).put("error", errorMessage);
        handler.addToolUseToHistory(toolCall, conversationHistory);
        handler.addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
