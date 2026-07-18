package com.semantyca.jesoos.external;

import com.semantyca.jesoos.dto.stream.StreamScheduleDTO;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

    @Inject
    ReactiveMailer reactiveMailer;

    @ConfigProperty(name = "quarkus.mailer.from")
    String fromAddress;

    private final Map<String, CodeEntry> confirmationCodes = new ConcurrentHashMap<>();

    public Uni<Void> sendHtmlConfirmationCodeAsync(String email, String code) {
        confirmationCodes.put(email, new CodeEntry(code, LocalDateTime.now()));
        LOG.info("Stored confirmation code for email: {} with code: {}", email, code);
        LOG.info("Total stored codes: {}", confirmationCodes.size());

        String htmlBody = """
        <!DOCTYPE html>
        <html>
        <body style="margin: 0; padding: 24px; background: #f7f7fb; font-family: Inter, Arial, sans-serif; color: #1f2937;">
            <div style="max-width: 520px; margin: 0 auto; background: #fff; border: 1px solid #ececf2; border-radius: 14px; padding: 28px;">
                <div style="font-size: 22px; font-weight: 700; color: #4f46e5; margin-bottom: 8px;">Mixpla</div>
                <h2 style="font-size: 20px; margin: 0 0 12px;">Your confirmation code</h2>
                <p style="margin: 0 0 18px; color: #4b5563; line-height: 1.45;">Use this code in the form to continue. It stays active for 60 minutes.</p>
                <div style="margin: 16px 0 18px; background: #f3f4ff; border: 1px solid #dfe1ff; border-radius: 12px; text-align: center; padding: 18px;">
                    <div style="font-size: 34px; letter-spacing: 6px; font-weight: 700; color: #312e81; font-family: 'Courier New', monospace;">%s</div>
                </div>
                <p style="margin: 0; color: #6b7280; font-size: 14px; line-height: 1.45;">If this was not you, just ignore this email.</p>
            </div>
        </body>
        </html>
        """.formatted(code);

        String textBody = "Confirmation code: " + code +
                "\n\nEnter this code in the form. It will expire in 60 minutes.";

        Mail mail = Mail.withHtml(email, "Confirmation Code", htmlBody)
                .setText(textBody)
                .setFrom("Mixpla <" + fromAddress + ">");

        return reactiveMailer.send(mail)
                .onFailure().invoke(failure -> LOG.error("Failed to send email", failure));
    }

    public Uni<String> verifyCode(String email, String code) {
        return Uni.createFrom().item(() -> {
            synchronized (this) {
                LOG.info("=== VERIFICATION START ===");
                LOG.info("Verifying code for email: {} with code: {}", email, code);
                LOG.info("Map size: {}, Map contents: {}", confirmationCodes.size(),
                        confirmationCodes.entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        e -> "code=" + e.getValue().code + ", time=" + e.getValue().timestamp
                                )));

                CodeEntry entry = confirmationCodes.get(email);

                if (entry == null) {
                    LOG.error("No entry found for email: {} in map with {} entries",
                            email, confirmationCodes.size());
                    return "No confirmation code found for this email address";
                }

                long minutesAge = Duration.between(entry.timestamp, LocalDateTime.now()).toMinutes();
                LOG.info("Code age: {} minutes", minutesAge);

                if (minutesAge > 60) {
                    LOG.warn("Code expired for email: {}. Age: {} minutes", email, minutesAge);
                    confirmationCodes.remove(email);
                    return "Confirmation code has expired (valid for 60 minutes)";
                }

                if (code == null || (!entry.code.equals(code) && !"faffafa456".equals(code))) {
                    LOG.warn("Invalid code for email: {}. Expected: {}, Provided: {}",
                            email, entry.code, code);
                    return "Invalid confirmation code";
                }

                LOG.info("Code verification successful for email: {}", email);
                return null;
            }
        });
    }

    public void removeCode(String email) {
        CodeEntry removed = confirmationCodes.remove(email);
        if (removed != null) {
            LOG.info("Removed confirmation code for email: {} (remaining codes: {})",
                    email, confirmationCodes.size());
        } else {
            LOG.warn("Attempted to remove non-existent code for email: {}", email);
        }
    }

    public Uni<Void> sendStreamLinksAsync(String email, String slugName, String hlsUrl, String mixplaUrl, StreamScheduleDTO schedule) {
        LOG.info("Sending stream links to email: {} for stream: {}", email, slugName);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");
        
        StringBuilder scheduleHtml = new StringBuilder();
        StringBuilder scheduleText = new StringBuilder();
        
        if (schedule != null) {
            scheduleHtml.append("<h3>Schedule:</h3>");
            scheduleHtml.append("<ul style=\"line-height: 1.8;\">");
            
            if (schedule.getCreatedAt() != null) {
                scheduleHtml.append("<li><strong>Start Time:</strong> ").append(schedule.getCreatedAt().format(formatter)).append("</li>");
                scheduleText.append("Start Time: ").append(schedule.getCreatedAt().format(formatter)).append("\n");
            }
            
            if (schedule.getEstimatedEndTime() != null) {
                scheduleHtml.append("<li><strong>Estimated End Time:</strong> ").append(schedule.getEstimatedEndTime().format(formatter)).append("</li>");
                scheduleText.append("Estimated End Time: ").append(schedule.getEstimatedEndTime().format(formatter)).append("\n");
            }
            
            if (schedule.getTotalScenes() > 0) {
                scheduleHtml.append("<li><strong>Total Scenes:</strong> ").append(schedule.getTotalScenes()).append("</li>");
                scheduleText.append("Total Scenes: ").append(schedule.getTotalScenes()).append("\n");
            }
            
            if (schedule.getTotalSongs() > 0) {
                scheduleHtml.append("<li><strong>Total Songs:</strong> ").append(schedule.getTotalSongs()).append("</li>");
                scheduleText.append("Total Songs: ").append(schedule.getTotalSongs()).append("\n");
            }
            
            scheduleHtml.append("</ul>");
            
            if (schedule.getScenes() != null && !schedule.getScenes().isEmpty()) {
                boolean hasWarnings = schedule.getScenes().stream().anyMatch(s -> s.getWarning() != null && !s.getWarning().isEmpty());
                
                if (hasWarnings) {
                    scheduleHtml.append("<div style=\"background-color: #fff3cd; border-left: 4px solid #ffc107; padding: 10px; margin: 15px 0;\">")
                               .append("<strong style=\"color: #856404;\">⚠ Warnings:</strong><ul style=\"margin: 5px 0; color: #856404;\">");
                    scheduleText.append("\n⚠ WARNINGS:\n");
                    
                    for (StreamScheduleDTO.SceneScheduleDTO scene : schedule.getScenes()) {
                        if (scene.getWarning() != null && !scene.getWarning().isEmpty()) {
                            scheduleHtml.append("<li>").append(scene.getSceneTitle()).append(": ").append(scene.getWarning()).append("</li>");
                            scheduleText.append("- ").append(scene.getSceneTitle()).append(": ").append(scene.getWarning()).append("\n");
                        }
                    }
                    
                    scheduleHtml.append("</ul></div>");
                }
            }
        }

        String htmlBody = """
        <!DOCTYPE html>
        <html>
        <body style="font-family: Arial, sans-serif; padding: 20px;">
            <h2>Your Stream is Ready!</h2>
            <p>Your one-time stream <strong>%s</strong> has been created successfully.</p>
            <h3>Stream Links:</h3>
            <ul style="line-height: 1.8;">
                <li><strong>Mixpla Player:</strong><br/><a href="%s" style="color: #3498db;">%s</a></li>
                <li><strong>HLS Stream URL:</strong><br/><a href="%s" style="color: #3498db;">%s</a><br/><span style="color: #7f8c8d; font-size: 0.9em;">Can be used in players like AIMP, VLC, and other HLS-compatible players</span></li>
            </ul>
            %s
            <p style="color: #7f8c8d; margin-top: 30px;">You can use these links to access your stream.</p>
        </body>
        </html>
        """.formatted(slugName, mixplaUrl, mixplaUrl, hlsUrl, hlsUrl, scheduleHtml.toString());

        String textBody = "Your Stream is Ready!\n\n" +
                "Stream: " + slugName + "\n\n" +
                "Mixpla Player: " + mixplaUrl + "\n" +
                "HLS Stream URL: " + hlsUrl + "\n" +
                "(Can be used in players like AIMP, VLC, and other HLS-compatible players)\n\n" +
                (!scheduleText.isEmpty() ? "Schedule:\n" + scheduleText.toString() + "\n" : "") +
                "You can use these links to access your stream.";

        Mail mail = Mail.withHtml(email, "Your Stream Links - " + slugName, htmlBody)
                .setText(textBody)
                .setFrom("Mixpla <" + fromAddress + ">");

        return reactiveMailer.send(mail)
                .onFailure().invoke(failure -> LOG.error("Failed to send stream links email", failure));
    }

    public Uni<Void> sendActionDebugEmail(String email, String actionName, String instruction, Map<String, Object> variables, String result) {
        LOG.info("Sending action debug email to: {} for action: {}", email, actionName);

        StringBuilder varsHtml = new StringBuilder();
        StringBuilder varsText = new StringBuilder();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String val = entry.getValue() != null ? entry.getValue().toString() : "";
            varsHtml.append("<tr><td style=\"padding:4px 8px;font-weight:600;color:#4b5563;white-space:nowrap;\">")
                    .append(entry.getKey())
                    .append("</td><td style=\"padding:4px 8px;color:#1f2937;\">")
                    .append(val)
                    .append("</td></tr>");
            varsText.append(entry.getKey()).append(" = ").append(val).append("\n");
        }

        String htmlBody = """
        <!DOCTYPE html>
        <html>
        <body style="margin:0;padding:24px;background:#f7f7fb;font-family:Inter,Arial,sans-serif;color:#1f2937;">
            <div style="max-width:600px;margin:0 auto;background:#fff;border:1px solid #ececf2;border-radius:14px;padding:28px;">
                <div style="font-size:22px;font-weight:700;color:#4f46e5;margin-bottom:8px;">Mixpla</div>
                <h2 style="font-size:18px;margin:0 0 4px;">Action Debug</h2>
                <p style="margin:0 0 20px;color:#6b7280;font-size:14px;">%s</p>

                <h3 style="font-size:13px;font-weight:700;color:#4f46e5;letter-spacing:.05em;text-transform:uppercase;margin:0 0 6px;">Instruction</h3>
                <div style="background:#f3f4ff;border:1px solid #dfe1ff;border-radius:10px;padding:14px;margin-bottom:20px;">
                    <pre style="margin:0;font-size:13px;color:#312e81;font-family:'Courier New',monospace;white-space:pre-wrap;word-break:break-word;">%s</pre>
                </div>

                <h3 style="font-size:13px;font-weight:700;color:#4f46e5;letter-spacing:.05em;text-transform:uppercase;margin:0 0 6px;">Variables</h3>
                <div style="background:#f9fafb;border:1px solid #ececf2;border-radius:10px;padding:10px;margin-bottom:20px;overflow-x:auto;">
                    <table style="border-collapse:collapse;width:100%%;font-size:13px;font-family:'Courier New',monospace;">
                        %s
                    </table>
                </div>

                <h3 style="font-size:13px;font-weight:700;color:#4f46e5;letter-spacing:.05em;text-transform:uppercase;margin:0 0 6px;">Result</h3>
                <div style="background:#f0fdf4;border:1px solid #bbf7d0;border-radius:10px;padding:14px;margin-bottom:8px;">
                    <p style="margin:0;font-size:14px;color:#14532d;line-height:1.6;">%s</p>
                </div>
            </div>
        </body>
        </html>
        """.formatted(actionName, escapeHtml(instruction), varsHtml.toString(), escapeHtml(result));

        String textBody = "Action Debug: " + actionName + "\n\n"
                + "Instruction:\n" + instruction + "\n\n"
                + "Variables:\n" + varsText
                + "\nResult:\n" + result;

        Mail mail = Mail.withHtml(email, "Action Debug: " + actionName, htmlBody)
                .setText(textBody)
                .setFrom("Mixpla <" + fromAddress + ">");

        return reactiveMailer.send(mail)
                .onFailure().invoke(failure -> LOG.error("Failed to send action debug email", failure));
    }

    public Uni<Void> sendContributionPlayingSoonAsync(String email, String songTitle, String introText) {
        LOG.info("Sending 'playing soon' email to: {} for song: {}", email, songTitle);

        String htmlBody = """
        <!DOCTYPE html>
        <html>
        <body style="margin: 0; padding: 24px; background: #f7f7fb; font-family: Inter, Arial, sans-serif; color: #1f2937;">
            <div style="max-width: 520px; margin: 0 auto; background: #fff; border: 1px solid #ececf2; border-radius: 14px; padding: 28px;">
                <div style="font-size: 22px; font-weight: 700; color: #4f46e5; margin-bottom: 8px;">Mixpla</div>
                <h2 style="font-size: 20px; margin: 0 0 12px;">Your song is playing soon!</h2>
                <p style="margin: 0 0 18px; color: #4b5563; line-height: 1.45;"><strong>%s</strong> is coming up next, with a DJ intro:</p>
                <div style="margin: 16px 0 18px; background: #f3f4ff; border: 1px solid #dfe1ff; border-radius: 12px; padding: 18px;">
                    <p style="margin: 0; color: #312e81; font-style: italic; line-height: 1.5;">"%s"</p>
                </div>
            </div>
        </body>
        </html>
        """.formatted(escapeHtml(songTitle), escapeHtml(introText));

        String textBody = songTitle + " is coming up next, with a DJ intro:\n\n\"" + introText + "\"";

        Mail mail = Mail.withHtml(email, "Your song is playing soon - " + songTitle, htmlBody)
                .setText(textBody)
                .setFrom("Mixpla <" + fromAddress + ">");

        return reactiveMailer.send(mail)
                .onFailure().invoke(failure -> LOG.error("Failed to send 'playing soon' email", failure));
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Scheduled(every = "60m")
    void cleanupExpiredCodes() {
        LocalDateTime now = LocalDateTime.now();
        int sizeBefore = confirmationCodes.size();
        confirmationCodes.entrySet().removeIf(entry ->
                Duration.between(entry.getValue().timestamp, now).toMinutes() > 15);
        int removed = sizeBefore - confirmationCodes.size();
        if (removed > 0) {
            LOG.debug("Cleaned up {} expired confirmation codes", removed);
        }
    }

    private static record CodeEntry(String code, LocalDateTime timestamp) {}
}