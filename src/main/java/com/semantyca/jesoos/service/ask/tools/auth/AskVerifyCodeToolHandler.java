package com.semantyca.jesoos.service.ask.tools.auth;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.ask.AskChatService;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import com.semantyca.jesoos.util.EmailUtil;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * Ask-scoped verify: stores session token only — no brand listener registration.
 */
public final class AskVerifyCodeToolHandler {

    private static final Logger LOG = Logger.getLogger(AskVerifyCodeToolHandler.class);
    private static final String METRIC_SCOPE = AskChatService.SCOPE_KEY;

    private AskVerifyCodeToolHandler() {}

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            PublicChatSessionManager sessionManager,
            UserService userService,
            String connectionId,
            MetricPublisher metricPublisher) {
        String email = EmailUtil.normalize((String) inputMap.getOrDefault("email", ""));
        String code = ((String) inputMap.getOrDefault("code", "")).trim();

        if (email.isBlank() || code.isBlank()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Email and code are required").encode()));
        }

        boolean valid = sessionManager.verifyAndConsumePendingOtp(email, code);
        if (!valid) {
            metricPublisher.publishMetric(METRIC_SCOPE, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                    "ask_login_failed", Map.of("email", email, "reason", "invalid_or_expired_code", "connectionId", connectionId));
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Invalid or expired verification code.").encode()));
        }

        return userService.findByEmail(email)
                .chain(user -> {
                    if (user == null || user.getId() == 0) {
                        metricPublisher.publishMetric(METRIC_SCOPE, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                "ask_login_failed", Map.of("email", email, "reason", "user_not_found", "connectionId", connectionId));
                        return Uni.createFrom().item(ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "User not found.").encode()));
                    }
                    String userToken = UUID.randomUUID().toString();
                    return sessionManager.storeUserToken(userToken, email)
                            .map(v -> {
                                metricPublisher.publishMetric(METRIC_SCOPE, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                        "ask_login_success", Map.of("email", email, "userId", user.getId(), "connectionId", connectionId));
                                String payload = new JsonObject()
                                        .put("ok", true).put("email", email)
                                        .put("userId", user.getId())
                                        .put("message", "Authentication successful.")
                                        .encode();
                                return ToolNodeResult.withAuth(payload, user.getId(), user, userToken, user.getLogin());
                            })
                            .onFailure().invoke(err -> LOG.errorf(err, "[AskVerifyCode] token store failed for %s", email))
                            .onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                                    new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
                });
    }
}
