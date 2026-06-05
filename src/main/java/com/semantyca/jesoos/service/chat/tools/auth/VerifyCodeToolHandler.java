package com.semantyca.jesoos.service.chat.tools.auth;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.chat.ChatService;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import com.semantyca.jesoos.util.EmailUtil;
import com.semantyca.jesoos.ws.PublicChatController;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.Map;

public class VerifyCodeToolHandler {

    private static final Logger LOG = Logger.getLogger(VerifyCodeToolHandler.class);

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            PublicChatSessionManager sessionManager,
            UserService userService,
            ChatService chatService,
            PublicChatController controller,
            String brandSlug,
            String connectionId,
            MetricPublisher metricPublisher) {
        String email = EmailUtil.normalize((String) inputMap.getOrDefault("email", ""));
        String code = ((String) inputMap.getOrDefault("code", "")).trim();
        String preferredName = ((String) inputMap.getOrDefault("preferred_name", "")).trim();

        if (email.isBlank() || code.isBlank()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Email and code are required").encode()));
        }

        boolean valid = sessionManager.verifyAndConsumePendingOtp(email, code);
        if (!valid) {
            metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                    "login_failed", Map.of("email", email, "reason", "invalid_or_expired_code", "connectionId", connectionId));
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Invalid or expired verification code.").encode()));
        }

        return userService.findByEmail(email)
                .chain(user -> {
                    if (user == null || user.getId() == 0) {
                        metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                "login_failed", Map.of("email", email, "reason", "user_not_found", "connectionId", connectionId));
                        return Uni.createFrom().item(ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "User not found.").encode()));
                    }
                    return chatService.registerListener(user, email, brandSlug, preferredName)
                            .onFailure().invoke(err -> LOG.errorf(err, "[VerifyCode] Listener registration failed for %s", email))
                            .onItem().transformToUni(reg -> {
                                metricPublisher.publishMetric(brandSlug, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                        "login_success", Map.of("email", email, "userId", user.getId(), "connectionId", connectionId));
                                String payload = new JsonObject()
                                        .put("ok", true).put("email", email)
                                        .put("userId", user.getId())
                                        .put("message", "Authentication successful! You now have access to all features and personalization.")
                                        .encode();
                                return Uni.createFrom().item(
                                        ToolNodeResult.withAuth(payload, user.getId(), user, reg.userToken(), user.getLogin()));
                            });
                });
    }
}
