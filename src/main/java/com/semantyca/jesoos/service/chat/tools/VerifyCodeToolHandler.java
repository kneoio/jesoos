package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.PublicChatService;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import com.semantyca.jesoos.ws.PublicChatController;
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
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn,
            ListenerService listenerService,
            ListenerLabelCache listenerLabelCache
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
            return handleError(toolUse, "Invalid or expired verification code. Please request a new one.",
                    handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        LOG.infof("[VerifyCode] Code verified for %s, upgrading user session", email);

        return userService.findByEmail(email)
                .onItem().transformToUni(user -> {
                    if (user == null || user.getId() == 0) {
                        LOG.warnf("[VerifyCode] User not found for email %s", email);
                        return handleError(toolUse, "User account not found. Please contact support.",
                                handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                    }

                    controller.upgradeUserSession(connectionId, user);
                    LOG.infof("[VerifyCode] User session upgraded in-place for %s (userId=%s)", email, user.getId());

                    Function<MessageCreateParams, Uni<Void>> authStreamFn =
                            chatService.createAuthStreamFn(chunkHandler, completionHandler, connectionId, brandSlug, user.getId());

                    return chatService.registerListener(email, brandSlug, preferredName)
                            .onItem().transformToUni(registrationResult -> {
                                LOG.infof("[VerifyCode] User registered as listener for station %s (userId=%s)", brandSlug, user.getId());

                                return listenerService.getByUserId(user.getId())
                                        .onItem().transformToUni(listener -> {
                                            JsonObject userData = new JsonObject();
                                            if (listener != null && listener.getUserData() != null
                                                    && listener.getUserData().getData() != null) {
                                                listener.getUserData().getData().forEach(
                                                        (k, v) -> userData.put(k, v != null ? v.toString() : null)
                                                );
                                            }
                                            java.util.UUID artistLabelId = listenerLabelCache.get("artist");
                                            boolean isArtist = artistLabelId != null
                                                    && listener != null
                                                    && listener.getLabels() != null
                                                    && listener.getLabels().contains(artistLabelId);
                                            if (isArtist) {
                                                userData.put("is_artist", "true");
                                            }

                                            // Send session token with user_data to frontend
                                            controller.sendToConnection(connectionId, new JsonObject()
                                                    .put("type", "session_token")
                                                    .put("token", registrationResult.userToken())
                                                    .put("email", email)
                                                    .put("user_data", userData)
                                                    .encode());

                                            JsonObject payload = new JsonObject()
                                                    .put("ok", true)
                                                    .put("email", email)
                                                    .put("userId", user.getId())
                                                    .put("message", "Authentication successful! You now have access to all features and personalization.");

                                            handler.addToolUseToHistory(toolUse, conversationHistory);
                                            handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                                            MessageCreateParams params = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                                            return authStreamFn.apply(params);
                                        })
                                        .onFailure().recoverWithUni(err -> {
                                            LOG.warnf("[VerifyCode] Failed to fetch listener data for %s, sending token without user_data: %s", user.getId(), err.getMessage());

                                            controller.sendToConnection(connectionId, new JsonObject()
                                                    .put("type", "session_token")
                                                    .put("token", registrationResult.userToken())
                                                    .put("email", email)
                                                    .encode());

                                            JsonObject payload = new JsonObject()
                                                    .put("ok", true)
                                                    .put("email", email)
                                                    .put("userId", user.getId())
                                                    .put("message", "Authentication successful! You now have access to all features and personalization.");

                                            handler.addToolUseToHistory(toolUse, conversationHistory);
                                            handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                                            MessageCreateParams params = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                                            return authStreamFn.apply(params);
                                        });
                            })
                            .onFailure().recoverWithUni(err -> {
                                LOG.warnf("[VerifyCode] Listener registration failed for %s, continuing anyway: %s", email, err.getMessage());

                                JsonObject payload = new JsonObject()
                                        .put("ok", true)
                                        .put("email", email)
                                        .put("userId", user.getId())
                                        .put("message", "Authentication successful! You now have access to all features.");

                                handler.addToolUseToHistory(toolUse, conversationHistory);
                                handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

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
