package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class SendEmailToOwnerToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SendEmailToOwnerToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            BrandService brandService,
            UserService userService,
            ReactiveMailer reactiveMailer,
            String fromAddress,
            long userId,
            String stationSlug,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        SendEmailToOwnerToolHandler handler = new SendEmailToOwnerToolHandler();
        String recipient = (String) inputMap.getOrDefault("recipient", "owner");
        String subject = (String) inputMap.getOrDefault("subject", "");
        String message = (String) inputMap.getOrDefault("message", "");

        if (subject.isBlank() || message.isBlank()) {
            return handleError(toolCall, "Subject and message are required", handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Sending email...");

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

            String userEmail = tuple.getItem1().get().getEmail();
            String toEmail;
            String sentTo;
            if ("self".equals(recipient)) {
                if (userEmail == null || userEmail.isBlank()) {
                    return Uni.createFrom().failure(new IllegalArgumentException("No email address on file for this listener"));
                }
                toEmail = userEmail;
                sentTo = "listener";
            } else {
                if (tuple.getItem2().getOwner() == null || tuple.getItem2().getOwner().getEmail() == null || tuple.getItem2().getOwner().getEmail().isBlank()) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Owner email not configured"));
                }
                toEmail = tuple.getItem2().getOwner().getEmail();
                sentTo = "owner";
            }

            String htmlBody = """
                    <!DOCTYPE html>
                    <html>
                    <body style="font-family: Arial, sans-serif; padding: 20px;">
                        <p><strong>Station:</strong> %s</p>
                        <p><strong>Subject:</strong> %s</p>
                        <hr style="border: 1px solid #ddd; margin: 20px 0;">
                        <div style="white-space: pre-wrap;">%s</div>
                    </body>
                    </html>
                    """.formatted(stationSlug, subject, message);

            String textBody = "Station: " + stationSlug + "\nSubject: " + subject + "\n\n" + message;

            Mail mail = Mail.withHtml(toEmail, subject, htmlBody)
                    .setText(textBody)
                    .setFrom("Mixpla <" + fromAddress + ">");
            if (!"self".equals(recipient)) {
                mail.setReplyTo(userEmail);
            }

            return reactiveMailer.send(mail)
                    .onFailure().invoke(failure -> LOGGER.error("Failed to send email", failure))
                    .replaceWith(sentTo);
        })
                .flatMap(sentTo -> {
                    JsonObject payload = new JsonObject().put("ok", true).put("sent_to", sentTo);
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Email sent!");
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[SendEmail] Failed - userId: {}, stationSlug: {}", userId, stationSlug, err);
                    return handleError(toolCall, "Failed to send email: " + err.getMessage(), handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
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
            io.quarkus.mailer.reactive.ReactiveMailer reactiveMailer, String fromAddress, long userId, String stationSlug) {
        String recipient = (String) inputMap.getOrDefault("recipient", "owner");
        String subject = (String) inputMap.getOrDefault("subject", "");
        String message = (String) inputMap.getOrDefault("message", "");
        LOGGER.infof("[SendEmail/execute] recipient=%s subject_len=%d message_len=%d userId=%d stationSlug=%s",
                recipient, subject.length(), message.length(), userId, stationSlug);
        if (subject.isBlank() || message.isBlank()) {
            LOGGER.warnf("[SendEmail/execute] rejected — subject or message blank (recipient=%s userId=%d)", recipient, userId);
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Subject and message are required").encode()));
        }
        return Uni.combine().all().unis(userService.findById(userId), brandService.getBySlugName(stationSlug)).asTuple()
                .chain(tuple -> {
                    if (tuple.getItem1().isEmpty() || tuple.getItem2() == null) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Could not resolve sender or recipient").encode()));
                    }
                    String userEmail = tuple.getItem1().get().getEmail();
                    String toEmail;
                    if ("self".equals(recipient)) {
                        if (userEmail == null || userEmail.isBlank()) {
                            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                    new JsonObject().put("ok", false).put("error", "No email address on file for this listener").encode()));
                        }
                        toEmail = userEmail;
                    } else {
                        if (tuple.getItem2().getOwner() == null || tuple.getItem2().getOwner().getEmail() == null) {
                            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                    new JsonObject().put("ok", false).put("error", "Owner email not configured").encode()));
                        }
                        toEmail = tuple.getItem2().getOwner().getEmail();
                    }
                    String htmlBody = """
                            <!DOCTYPE html>
                            <html>
                            <body style="font-family: Arial, sans-serif; padding: 20px;">
                                <p><strong>Station:</strong> %s</p>
                                <p><strong>Subject:</strong> %s</p>
                                <hr style="border: 1px solid #ddd; margin: 20px 0;">
                                <div style="white-space: pre-wrap; line-height: 1.6;">%s</div>
                            </body>
                            </html>
                            """.formatted(stationSlug, subject, message);
                    String textBody = "Station: " + stationSlug + "\nSubject: " + subject + "\n\n" + message.replaceAll("<[^>]+>", "");
                    Mail mail = Mail.withHtml(toEmail, subject, htmlBody)
                            .setText(textBody)
                            .setFrom("Mixpla <" + fromAddress + ">");
                    if (!"self".equals(recipient)) {
                        mail.setReplyTo(userEmail);
                    }
                    return reactiveMailer.send(mail)
                            .map(v -> {
                                LOGGER.infof("[SendEmail/execute] sent ok to=%s recipient=%s userId=%d", toEmail, recipient, userId);
                                return com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                        new JsonObject().put("ok", true).put("sent_to", recipient).encode());
                            })
                            .onFailure().recoverWithItem(err -> {
                                LOGGER.errorf(err, "[SendEmail/execute] send failed to=%s recipient=%s userId=%d", toEmail, recipient, userId);
                                return com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                        new JsonObject().put("ok", false).put("error", "Failed to send: " + err.getMessage()).encode());
                            });
                });
    }
}
