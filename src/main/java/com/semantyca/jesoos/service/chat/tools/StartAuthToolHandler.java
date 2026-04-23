package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.external.KeycloakAuthService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class StartAuthToolHandler extends BaseToolHandler {

    private static final Logger LOG = Logger.getLogger(StartAuthToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            KeycloakAuthService keycloakAuthService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        StartAuthToolHandler handler = new StartAuthToolHandler();
        String email = inputMap.getOrDefault("email", JsonValue.from(""))
                .toString().replace("\"", "").trim();

        if (email.isBlank()) {
            return handleError(toolUse, "Email address is required", handler,
                    chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        LOG.infof("[StartAuth] Initiating auth for email: %s", email);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Sending verification code to " + email + "...");

        return keycloakAuthService.startAuth(email)
                .flatMap(success -> {
                    JsonObject payload = new JsonObject()
                            .put("ok", success)
                            .put("email", email)
                            .put("message", success
                                    ? "Verification code sent to " + email
                                    : "Failed to send verification code to " + email);

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams params = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(params);
                })
                .onFailure().recoverWithUni(err -> {
                    LOG.error("[StartAuth] Failed for email: {}", email, err);
                    return handleError(toolUse, "Could not send verification code: " + err.getMessage(),
                            handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(
            ToolUseBlock toolUse,
            String errorMessage,
            StartAuthToolHandler handler,
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
