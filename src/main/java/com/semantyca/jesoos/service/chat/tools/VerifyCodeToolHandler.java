package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class VerifyCodeToolHandler extends BaseToolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(VerifyCodeToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            PublicChatSessionManager sessionManager,
            Consumer<String> chunkHandler,
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

        if (email.isBlank() || code.isBlank()) {
            return handleError(toolUse, "Email and verification code are both required", handler,
                    chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        LOG.info("[VerifyCode] Verifying code for email: {}", email);
        handler.sendProcessingChunk(chunkHandler, connectionId, "Verifying code...");

        boolean valid = sessionManager.verifyAndConsumePendingOtp(email, code);
        if (!valid) {
            LOG.warn("[VerifyCode] Invalid or expired code for {}", email);
            return handleError(toolUse, "Invalid or expired verification code. Please request a new one.",
                    handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        String sessionToken = UUID.randomUUID().toString();
        sessionManager.storeUserToken(sessionToken, email);
        LOG.info("[VerifyCode] Authentication complete for {}, session created", email);

        JsonObject authTokenMsg = new JsonObject()
                .put("type", "AUTH_TOKEN")
                .put("token", sessionToken)
                .put("email", email);
        chunkHandler.accept(authTokenMsg.encode());

        JsonObject payload = new JsonObject()
                .put("ok", true)
                .put("email", email)
                .put("message", "Authentication successful");

        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

        MessageCreateParams params = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(params);
    }

    private static Uni<Void> handleError(
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
