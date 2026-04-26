package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.chat.PublicChatService;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
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
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            PublicChatSessionManager sessionManager,
            UserService userService,
            PublicChatController controller,
            PublicChatService chatService,
            String brandSlug,
            MetricPublisher metricPublisher,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        VerifyCodeToolHandler handler = new VerifyCodeToolHandler();
        String email = inputMap.getOrDefault("email", JsonValue.from(""))
                .toString().replace("\"", "").trim();
        String code = inputMap.getOrDefault("code", JsonValue.from(""))
                .toString().replace("\"", "").trim();
        String preferredName = inputMap.getOrDefault("preferred_name", JsonValue.from(""))
                .toString().replace("\"", "").trim();

        if (email.isBlank() || code.isBlank()) {
            return handleError(toolUse, "Email and verification code are both required", handler,
                    chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        LOG.infof("[VerifyCode] Verifying code for email: %s", email);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Verifying code...");

        boolean valid = sessionManager.verifyAndConsumePendingOtp(email, code);
        if (!valid) {
            LOG.warnf("[VerifyCode] Invalid or expired code for %s", email);
            metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                    "login_failed", Map.of("email", email, "reason", "invalid_or_expired_code", "connectionId", connectionId));
            return handleError(toolUse, "Invalid or expired verification code. Please request a new one.",
                    handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        LOG.infof("[VerifyCode] Code verified for %s, upgrading user session", email);

        return userService.findByEmail(email)
                .onItem().transformToUni(user -> {
                    if (user == null || user.getId() == 0) {
                        LOG.warnf("[VerifyCode] User not found for email %s", email);
                        metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                "login_failed", Map.of("email", email, "reason", "user_not_found", "connectionId", connectionId));
                        return handleError(toolUse, "User account not found. Please contact support.",
                                handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                    }

                    controller.upgradeUserSession(connectionId, user);
                    LOG.infof("[VerifyCode] User session upgraded in-place for %s (userId=%s)", email, user.getId());

                    Function<MessageCreateParams, Uni<Void>> authStreamFn =
                            chatService.createAuthStreamFn(chunkHandler, completionHandler, connectionId, brandSlug, user.getId());

                    return chatService.registerListener(email, brandSlug, preferredName)
                            .onFailure().recoverWithItem(err -> {
                                LOG.warnf("[VerifyCode] Listener registration failed for %s, continuing anyway: %s", email, err.getMessage());
                                return null;
                            })
                            .onItem().transformToUni(registrationResult -> {
                                boolean registered = registrationResult != null;
                                if (registered) {
                                    LOG.infof("[VerifyCode] User registered as listener for station %s (userId=%s)", brandSlug, user.getId());
                                    metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                            "login_success", Map.of(
                                                    "email", email,
                                                    "userId", user.getId(),
                                                    "connectionId", connectionId,
                                                    "historySize", conversationHistory.size()
                                            ));
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
                                        .put("ok", true)
                                        .put("email", email)
                                        .put("userId", user.getId())
                                        .put("message", message);

                                handler.addToolUseToHistory(toolUse, conversationHistory);
                                handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
                                LOG.infof("[VerifyCode] syncing history for userId=%d size=%d lastRole=%s",
                                        user.getId(), conversationHistory.size(),
                                        conversationHistory.isEmpty() ? "n/a" : conversationHistory.get(conversationHistory.size() - 1).role().toString());
                                chatService.syncConversationHistory(connectionId, user.getId(), conversationHistory);

                                MessageCreateParams params = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                                return authStreamFn.apply(params);
                            });
                });
    }

    static Uni<Void> handleError(
            ToolUseBlock toolUse,
            String errorMessage,
            VerifyCodeToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        JsonObject errorPayload = new JsonObject()
                .put("ok", false)
                .put("error", errorMessage);

        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

        MessageCreateParams params = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(params);
    }
}
