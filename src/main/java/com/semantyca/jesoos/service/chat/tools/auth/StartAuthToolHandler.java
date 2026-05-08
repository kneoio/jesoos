package com.semantyca.jesoos.service.chat.tools.auth;

import com.semantyca.jesoos.external.KeycloakAuthService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.chat.tools.BaseToolHandler;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class StartAuthToolHandler extends BaseToolHandler {

    private static final Logger LOG = Logger.getLogger(StartAuthToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            KeycloakAuthService keycloakAuthService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        StartAuthToolHandler handler = new StartAuthToolHandler();
        String email = ((String) inputMap.getOrDefault("email", "")).trim().toLowerCase(Locale.ROOT);

        if (email.isBlank()) {
            return handleError(toolCall, "Email address is required", handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        LOG.infof("[StartAuth] Initiating auth for email: %s", email);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Sending verification code to " + email + "...");

        return keycloakAuthService.startAuth(email)
                .flatMap(success -> {
                    JsonObject payload = new JsonObject()
                            .put("ok", success).put("email", email)
                            .put("message", success ? "Verification code sent to " + email : "Failed to send verification code to " + email);
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOG.error("[StartAuth] Failed for email: {}", email, err);
                    return handleError(toolCall, "Could not send verification code: " + err.getMessage(), handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(LlmToolCall toolCall, String errorMessage, StartAuthToolHandler handler,
                                         Consumer<String> chunkHandler, String connectionId,
                                         List<LlmMessage> conversationHistory, String systemPromptCall2,
                                         Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject errorPayload = new JsonObject().put("ok", false).put("error", errorMessage);
        handler.addToolUseToHistory(toolCall, conversationHistory);
        handler.addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
