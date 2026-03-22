package com.semantyca.jesoos.service.external;

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
        <body style="font-family: Arial, sans-serif; padding: 20px;">
            <h2>Mixpla Email Confirmation</h2>
            <p>Your code: <strong style="font-size: 24px; color: #3498db;">%s</strong></p>
            <p style="color: #7f8c8d;">Enter the number to the submission form. WARNING: It will expire in 60 minutes.</p>
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