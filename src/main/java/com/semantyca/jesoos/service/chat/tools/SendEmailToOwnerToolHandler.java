package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.service.BrandService;
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
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            BrandService brandService,
            UserService userService,
            ReactiveMailer reactiveMailer,
            String fromAddress,
            long userId,
            String stationSlug,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        SendEmailToOwnerToolHandler handler = new SendEmailToOwnerToolHandler();
        String subject = inputMap.getOrDefault("subject", JsonValue.from("")).toString().replace("\"", "");
        String message = inputMap.getOrDefault("message", JsonValue.from("")).toString().replace("\"", "");

        if (subject.isBlank() || message.isBlank()) {
            return handleError(toolUse, "Subject and message are required", handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
        }

        LOGGER.info("[SendEmailToOwner] Starting - userId: {}, stationSlug: {}, subject: {}", userId, stationSlug, subject);

        handler.sendProcessingChunk(chunkHandler, connectionId, "Sending email to owner...");

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
            String ownerEmail = tuple.getItem2().getOwner().getEmail();

            String htmlBody = """
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; padding: 20px;">
                <h2>Message from Listener</h2>
                <p><strong>From:</strong> %s</p>
                <p><strong>Station:</strong> %s</p>
                <p><strong>Subject:</strong> %s</p>
                <hr style="border: 1px solid #ddd; margin: 20px 0;">
                <div style="white-space: pre-wrap;">%s</div>
            </body>
            </html>
            """.formatted(userEmail, stationSlug, subject, message);

            String textBody = "Message from Listener\n\n" +
                    "From: " + userEmail + "\n" +
                    "Station: " + stationSlug + "\n" +
                    "Subject: " + subject + "\n\n" +
                    message;

            Mail mail = Mail.withHtml(ownerEmail, "Listener Message: " + subject, htmlBody)
                    .setText(textBody)
                    .setFrom("Mixpla <" + fromAddress + ">")
                    .setReplyTo(userEmail);

            return reactiveMailer.send(mail)
                    .onFailure().invoke(failure -> LOGGER.error("Failed to send email to owner", failure))
                    .replaceWith(ownerEmail);
        })
                .flatMap(ownerEmail -> {
                    LOGGER.info("[SendEmailToOwner] Email sent successfully to: {}", ownerEmail);

                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("message", "Email sent successfully to station owner");

                    handler.sendProcessingChunk(chunkHandler, connectionId, "Email sent successfully!");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[SendEmailToOwner] Failed - userId: {}, stationSlug: {}", userId, stationSlug, err);
                    return handleError(toolUse, "Failed to send email: " + err.getMessage(), handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(
            ToolUseBlock toolUse,
            String errorMessage,
            SendEmailToOwnerToolHandler handler,
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

        MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(secondCallParams);
    }
}
