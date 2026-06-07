package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.agenda.TimelineEntryDTO;
import com.semantyca.jesoos.service.PlaylistQueueService;
import com.semantyca.jesoos.service.agenda.AgendaViewService;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.time.format.DateTimeFormatter;
import java.util.Map;

public class StreamInfoToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(StreamInfoToolHandler.class);

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            PlaylistQueueService playlistQueueService,
            AgendaViewService agendaViewService,
            String brandName,
            ReactiveMailer reactiveMailer,
            UserService userService,
            long userId,
            String fromAddress
    ) {
        String action = (String) inputMap.getOrDefault("action", "get_current_scene");
        LOGGER.infof("[StreamInfo] action=%s, brand=%s", action, brandName);

        return switch (action) {
            case "get_today_agenda" -> buildAgendaResult(agendaViewService, brandName);
            case "email_today_agenda" -> sendAgendaEmail(agendaViewService, reactiveMailer, userService,
                    userId, brandName, fromAddress);
            default -> playlistQueueService.getQueueByBrandSlug(brandName)
                    .map(queue -> ToolNodeResult.ok(new JsonObject().put("ok", true).put("queue", queue).encode()))
                    .onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
        };
    }

    private static Uni<ToolNodeResult> buildAgendaResult(AgendaViewService agendaViewService, String brandName) {
        return Uni.createFrom().item(() -> {
            var result = buildAgendaContent(agendaViewService, brandName);
            if (result == null) {
                return ToolNodeResult.ok(new JsonObject().put("ok", false).put("error", "No agenda available").encode());
            }
            return ToolNodeResult.ok(new JsonObject()
                    .put("ok", true)
                    .put("agenda", result.entries)
                    .put("formatted", result.formatted)
                    .encode());
        }).onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }

    private static Uni<ToolNodeResult> sendAgendaEmail(
            AgendaViewService agendaViewService,
            ReactiveMailer reactiveMailer,
            UserService userService,
            long userId,
            String brandName,
            String fromAddress
    ) {
        return userService.findById(userId).chain(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "User not found").encode()));
            }
            String toEmail = userOpt.get().getEmail();
            if (toEmail == null || toEmail.isBlank()) {
                return Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "No email address on file").encode()));
            }

            AgendaContent content = buildAgendaContent(agendaViewService, brandName);
            if (content == null) {
                return Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "No agenda available").encode()));
            }

            String subject = "Today's schedule on " + brandName;
            String htmlBody = """
                    <!DOCTYPE html>
                    <html>
                    <body style="font-family: Arial, sans-serif; padding: 20px;">
                        <p><strong>Station:</strong> %s</p>
                        <hr style="border: 1px solid #ddd; margin: 20px 0;">
                        <div style="white-space: pre-wrap; line-height: 1.8; font-size: 14px;">%s</div>
                    </body>
                    </html>
                    """.formatted(brandName, content.formatted);
            String textBody = "Station: " + brandName + "\n\n"
                    + content.formatted.replaceAll("<[^>]+>", "");

            Mail mail = Mail.withHtml(toEmail, subject, htmlBody)
                    .setText(textBody)
                    .setFrom("Mixpla <" + fromAddress + ">");

            LOGGER.infof("[StreamInfo/email_today_agenda] sending to=%s brand=%s userId=%d", toEmail, brandName, userId);
            return reactiveMailer.send(mail)
                    .map(v -> {
                        LOGGER.infof("[StreamInfo/email_today_agenda] sent ok to=%s", toEmail);
                        return ToolNodeResult.ok(new JsonObject().put("ok", true).put("sent_to", toEmail).encode());
                    })
                    .onFailure().recoverWithItem(err -> {
                        LOGGER.errorf(err, "[StreamInfo/email_today_agenda] failed to=%s", toEmail);
                        return ToolNodeResult.ok(new JsonObject().put("ok", false).put("error", "Failed to send: " + err.getMessage()).encode());
                    });
        });
    }

    private static AgendaContent buildAgendaContent(AgendaViewService agendaViewService, String brandName) {
        var agenda = agendaViewService.getAgendaByBrand(brandName);
        if (agenda == null) return null;

        var entries = new io.vertx.core.json.JsonArray();
        var formatted = new StringBuilder();
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        String generatedAt = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm, dd MMM yyyy"));
        formatted.append("<span style=\"color:#7f8c8d;font-size:11px;\">Generated at ").append(generatedAt).append("</span>\n\n");

        if (agenda.getScenes() != null) {
            for (var scene : agenda.getScenes()) {
                if (scene.getTimeline() == null) continue;
                for (var entry : scene.getTimeline()) {
                    if (entry.getSongs() == null) continue;
                    String timeStr = entry.getScheduledEmissionTime() != null
                            ? entry.getScheduledEmissionTime().format(timeFmt) : "--:--";
                    String status = entry.getStatus() != null ? entry.getStatus().toString() : "";
                    String statusColor = switch (status) {
                        case "COMPLETED"  -> "#27ae60";
                        case "EMITTING"   -> "#2980b9";
                        case "PENDING"    -> "#7f8c8d";
                        case "SCHEDULED"  -> "#9b59b6";
                        case "SKIPPED"    -> "#e67e22";
                        case "FAILED"     -> "#c0392b";
                        default           -> "#95a5a6";
                    };
                    for (TimelineEntryDTO.SongDTO song : entry.getSongs()) {
                        entries.add(new JsonObject()
                                .put("time", timeStr)
                                .put("artist", song.getArtist())
                                .put("title", song.getSongTitle())
                                .put("status", status));
                        formatted.append(timeStr)
                                .append("  ")
                                .append(song.getArtist())
                                .append(" — ")
                                .append(song.getSongTitle())
                                .append("  <span style=\"color:").append(statusColor)
                                .append(";font-size:11px;\">").append(status).append("</span>")
                                .append("\n");
                    }
                }
            }
        }

        String formattedText = formatted.toString().stripTrailing()
                + "\n\n⚠ This is an estimated plan. The DJ may adjust the order or skip tracks during the live stream."
                + "\n\n—\nDiscover more stations at https://mixpla.online";
        return new AgendaContent(entries, formattedText);
    }

    private record AgendaContent(io.vertx.core.json.JsonArray entries, String formatted) {}
}
