package com.semantyca.jesoos.service.ask.tools.auth;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.ask.AskChatService;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import com.semantyca.jesoos.ws.AskChatController;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.Map;

public final class AskLogoffToolHandler {

    private static final String METRIC_SCOPE = AskChatService.SCOPE_KEY;

    private AskLogoffToolHandler() {}

    public static Uni<ToolNodeResult> execute(
            PublicChatSessionManager sessionManager,
            UserService userService,
            AskChatController controller,
            AskChatService askChatService,
            MetricPublisher metricPublisher,
            long userId,
            String connectionId) {
        return userService.findById(userId)
                .chain(opt -> {
                    if (opt.isEmpty() || opt.get().getEmail() == null) {
                        return Uni.createFrom().item(ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "No active session found").encode()));
                    }
                    String email = opt.get().getEmail();
                    return sessionManager.deleteTokenByEmail(email)
                            .map(v -> {
                                controller.downgradeUserSession(connectionId);
                                askChatService.clearConversationHistory(connectionId, userId);
                                metricPublisher.publishMetric(METRIC_SCOPE, MetricEventType.IMPORTANT_INFORMATION, ProcessType.INDEPENDENT,
                                        "ask_logoff", Map.of("email", email, "userId", userId, "connectionId", connectionId));
                                String wsMessage = new JsonObject().put("type", "session_token").put("token", (Object) null).encode();
                                String payload = new JsonObject().put("ok", true).put("message", "Logged out successfully").encode();
                                return ToolNodeResult.logoff(payload, wsMessage);
                            });
                })
                .onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "Logoff failed: " + err.getMessage()).encode()));
    }
}
