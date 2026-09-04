package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.service.UserService;
import com.semantyca.core.service.mail.MailService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class SendEmailToOwnerToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(SendEmailToOwnerToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            BrandService brandService,
            UserService userService,
            MailService mailService,
            long userId,
            String stationSlug,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        SendEmailToOwnerToolHandler handler = new SendEmailToOwnerToolHandler();
        String subject = (String) inputMap.getOrDefault("subject", "");
        String message = (String) inputMap.getOrDefault("message", "");

        if (subject.isBlank() || message.isBlank()) {
            return handleError(toolCall, "Subject and message are required", handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Sending message to owner...");

        return Uni.combine().all().unis(
                userService.findById(userId),
                brandService.getBySlugName(stationSlug)
        ).asTuple().chain(tuple -> {
            if (tuple.getItem1().isEmpty()) {
                return Uni.createFrom().failure(new IllegalArgumentException("User not found"));
            }
            if (tuple.getItem2() == null) {
                return Uni.createFrom().failure(new IllegalArgumentException("Station not found"));
            }
            if (tuple.getItem2().getOwner() == null || tuple.getItem2().getOwner().getEmail() == null || tuple.getItem2().getOwner().getEmail().isBlank()) {
                return Uni.createFrom().failure(new IllegalArgumentException("Owner email not configured"));
            }

            String userEmail = tuple.getItem1().get().getEmail();
            String toEmail = tuple.getItem2().getOwner().getEmail();
            return mailService.sendMessageToOwner(toEmail, userEmail, subject, stationSlug, message)
                    .replaceWith("owner");
        })
                .flatMap(sentTo -> {
                    JsonObject payload = new JsonObject().put("ok", true).put("sent_to", sentTo);
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Message sent to owner!");
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.errorf("[InformOwner] Failed - userId: %d, stationSlug: %s", userId, stationSlug, err);
                    return handleError(toolCall, "Failed to send message: " + err.getMessage(), handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(LlmToolCall toolCall, String errorMessage,
                                         SendEmailToOwnerToolHandler handler,
                                         Consumer<String> chunkHandler, String connectionId,
                                         List<LlmMessage> conversationHistory, String systemPromptCall2,
                                         Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject errorPayload = new JsonObject().put("ok", false).put("error", errorMessage);
        handler.addToolUseToHistory(toolCall, conversationHistory);
        handler.addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap, BrandService brandService, UserService userService,
            MailService mailService, long userId, String stationSlug) {
        String subject = (String) inputMap.getOrDefault("subject", "");
        String message = (String) inputMap.getOrDefault("message", "");
        LOGGER.infof("[InformOwner/execute] subject_len=%d message_len=%d userId=%d stationSlug=%s",
                subject.length(), message.length(), userId, stationSlug);
        if (subject.isBlank() || message.isBlank()) {
            LOGGER.warnf("[InformOwner/execute] rejected — subject or message blank (userId=%d)", userId);
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Subject and message are required").encode()));
        }
        return Uni.combine().all().unis(userService.findById(userId), brandService.getBySlugName(stationSlug)).asTuple()
                .chain(tuple -> {
                    if (tuple.getItem1().isEmpty() || tuple.getItem2() == null) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Could not resolve sender or station").encode()));
                    }
                    if (tuple.getItem2().getOwner() == null || tuple.getItem2().getOwner().getEmail() == null) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Owner email not configured").encode()));
                    }
                    String userEmail = tuple.getItem1().get().getEmail();
                    String toEmail = tuple.getItem2().getOwner().getEmail();
                    return mailService.sendMessageToOwner(toEmail, userEmail, subject, stationSlug, message)
                            .map(v -> {
                                LOGGER.infof("[InformOwner/execute] sent ok to=%s userId=%d", toEmail, userId);
                                return com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                        new JsonObject().put("ok", true).put("sent_to", "owner").encode());
                            })
                            .onFailure().recoverWithItem(err -> {
                                LOGGER.errorf(err, "[InformOwner/execute] send failed to=%s userId=%d", toEmail, userId);
                                return com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                        new JsonObject().put("ok", false).put("error", "Failed to send: " + err.getMessage()).encode());
                            });
                });
    }
}
