package com.semantyca.jesoos.service.chat.tools.auth;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.chat.PublicChatService;
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

public class VerifyCodeToolHandler extends BaseToolHandler {

    private static final Logger LOG = Logger.getLogger(VerifyCodeToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            PublicChatSessionManager sessionManager,
            UserService userService,
            PublicChatController controller,
            PublicChatService chatService,
            String brandSlug,
            MetricPublisher metricPublisher,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        VerifyCodeToolHandler handler = new VerifyCodeToolHandler();
        String email = ((String) inputMap.getOrDefault("email", "")).trim();
        String code = ((String) inputMap.getOrDefault("code", "")).trim();
        String preferredName = ((String) inputMap.getOrDefault("preferred_name", "")).trim();

        if (email.isBlank() || code.isBlank()) {
            return handleError(toolCall, "Email and verification code are both required", handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        LOG.infof("[VerifyCode] Verifying code for email: %s", email);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Verifying code...");

        boolean valid = sessionManager.verifyAndConsumePendingOtp(email, code);
        if (!valid) {
            LOG.warnf("[VerifyCode] Invalid or expired code for %s", email);
            metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                    "login_failed", Map.of("email", email, "reason", "invalid_or_expired_code", "connectionId", connectionId));
            return handleError(toolCall, "Invalid or expired verification code. Please request a new one.",
                    handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        return userService.findByEmail(email)
                .onItem().transformToUni(user -> {
                    if (user == null || user.getId() == 0) {
                        metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                "login_failed", Map.of("email", email, "reason", "user_not_found", "connectionId", connectionId));
                        return handleError(toolCall, "User account not found. Please contact support.",
                                handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                    }

                    controller.upgradeUserSession(connectionId, user);

                    Function<LlmRequest, Uni<Void>> authStreamFn =
                            chatService.createAuthStreamFn(chunkHandler, completionHandler, connectionId, brandSlug, user.getId());

                    return chatService.registerListener(email, brandSlug, preferredName)
                            .onFailure().recoverWithItem(err -> {
                                LOG.warnf("[VerifyCode] Listener registration failed for %s, continuing anyway: %s", email, err.getMessage());
                                return null;
                            })
                            .onItem().transformToUni(registrationResult -> {
                                boolean registered = registrationResult != null;
                                if (registered) {
                                    metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                            "login_success", Map.of("email", email, "userId", user.getId(),
                                                    "connectionId", connectionId, "historySize", conversationHistory.size()));
                                    controller.sendToConnection(connectionId, new JsonObject()
                                            .put("type", "session_token")
                                            .put("token", registrationResult.userToken())
                                            .put("userName", user.getLogin())
                                            .encode());
                                }

                                String message = registered
                                        ? "Authentication successful! You now have access to all features and personalization."
                                        : "Authentication successful! You now have access to all features.";

                                JsonObject payload = new JsonObject()
                                        .put("ok", true).put("email", email).put("userId", user.getId()).put("message", message);

                                handler.addToolUseToHistory(toolCall, conversationHistory);
                                handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                                LOG.infof("[VerifyCode] syncing history for userId=%d size=%d lastRole=%s",
                                        user.getId(), conversationHistory.size(),
                                        conversationHistory.isEmpty() ? "n/a" : conversationHistory.getLast().role().name());
                                chatService.syncConversationHistory(connectionId, user.getId(), conversationHistory);

                                return authStreamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                            });
                });
    }

    static Uni<Void> handleError(LlmToolCall toolCall, String errorMessage, VerifyCodeToolHandler handler,
                                 Consumer<String> chunkHandler, String connectionId,
                                 List<LlmMessage> conversationHistory, String systemPromptCall2,
                                 Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject errorPayload = new JsonObject().put("ok", false).put("error", errorMessage);
        handler.addToolUseToHistory(toolCall, conversationHistory);
        handler.addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
