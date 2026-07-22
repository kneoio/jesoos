package com.semantyca.jesoos.external;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

    @Inject
    ReactiveMailer reactiveMailer;

    @ConfigProperty(name = "quarkus.mailer.from")
    String fromAddress;

    private final Map<String, CodeEntry> confirmationCodes = new ConcurrentHashMap<>();

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

    private static final java.time.format.DateTimeFormatter PLAYING_SOON_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm zzz", java.util.Locale.ENGLISH);

    public Uni<Void> sendContributionPlayingSoonAsync(String email, String songTitle, String stationUrl, String brandName, String djName,
                                                        int etaSeconds, java.time.ZoneId brandZone) {
        LOG.info("Sending 'playing soon' email to: {} for song: {}", email, songTitle);

        String listenLabel = brandName != null && !brandName.isBlank()
                ? "Listen " + escapeHtml(brandName) + " now..."
                : "Listen live now...";

        boolean hasDjName = djName != null && !djName.isBlank();
        String playingClauseHtml = hasDjName ? "I'm about to play it" : "the DJ is about to play it";
        String playingClauseText = hasDjName ? "I'm about to play it" : "the DJ is about to play it";
        String djLineHtml = hasDjName ? "— Your DJ, " + escapeHtml(djName) : "";
        String djLineText = hasDjName ? "— Your DJ, " + djName : "";

        java.time.ZoneId zone = brandZone != null ? brandZone : java.time.ZoneId.systemDefault();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
        java.time.ZonedDateTime estimatedPlayTime = now.plusSeconds(Math.max(etaSeconds, 0));
        String nowLabel = now.format(PLAYING_SOON_TIME_FORMAT);
        String etaLabel = estimatedPlayTime.format(PLAYING_SOON_TIME_FORMAT);
        String roughDuration = formatRoughDuration(etaSeconds);

        String skipWarningHtml = hasDjName
                ? "Heads up — that's just my best guess, so if I shuffle the lineup at the last second, don't hold it against me!"
                : "Heads up — that's just a rough guess, and the DJ might still shuffle the lineup at the last second.";

        String htmlBody = """
        <!DOCTYPE html>
        <html>
        <body style="margin: 0; padding: 24px; background: #f7f7fb; font-family: Inter, Arial, sans-serif; color: #1f2937;">
            <div style="max-width: 520px; margin: 0 auto; background: #fff; border: 1px solid #ececf2; border-radius: 14px; padding: 28px;">
                <div style="font-size: 22px; font-weight: 700; color: #4f46e5; margin-bottom: 8px;">Mixpla</div>
                <p style="margin: 0 0 12px; color: #4b5563;">Hi,</p>
                <h2 style="font-size: 20px; margin: 0 0 12px;">Your song is playing soon!</h2>
                <p style="margin: 0 0 18px; color: #4b5563; line-height: 1.45;"><strong>%s</strong> is now in the queue — %s, roughly in %s (around %s, current time %s).</p>
                <div style="margin: 16px 0 18px; background: #f3f4ff; border: 1px solid #dfe1ff; border-radius: 12px; text-align: center; padding: 18px;">
                    <a href="%s" style="color: #4f46e5; font-weight: 700; text-decoration: none;">%s</a>
                </div>
                <p style="margin: 0 0 18px; color: #4b5563; line-height: 1.45;"><a href="%s" style="color: #4f46e5; font-weight: 700; text-decoration: none;">Chat and ask for your next song to play</a></p>
                <p style="margin: 0 0 18px; color: #6b7280; font-size: 14px; line-height: 1.45; font-style: italic;">%s</p>
                <p style="margin: 0 0 18px; color: #4b5563; line-height: 1.45;">%s</p>
                <p style="margin: 0; color: #6b7280; font-size: 14px; line-height: 1.45;">If this was not you, just ignore this email.</p>
            </div>
        </body>
        </html>
        """.formatted(escapeHtml(songTitle), playingClauseHtml, roughDuration, etaLabel, nowLabel,
                stationUrl, listenLabel, stationUrl, skipWarningHtml, djLineHtml);

        String textBody = "Hi,\n\n" + songTitle + " is now in the queue — " + playingClauseText
                + ", roughly in " + roughDuration + " (around " + etaLabel + ", current time " + nowLabel + ").\n\n" + stationUrl
                + "\n\nChat and ask for your next song to play."
                + "\n\n" + skipWarningHtml
                + (djLineText.isEmpty() ? "" : "\n\n" + djLineText);

        Mail mail = Mail.withHtml(email, "Your song is playing soon - " + songTitle, htmlBody)
                .setText(textBody)
                .setFrom("Mixpla <" + fromAddress + ">");

        return reactiveMailer.send(mail)
                .onFailure().invoke(failure -> LOG.error("Failed to send 'playing soon' email", failure));
    }

    private static String formatRoughDuration(int seconds) {
        if (seconds < 60) {
            return Math.max(seconds, 0) + " sec";
        }
        long minutes = Math.round(seconds / 60.0);
        return minutes + " min";
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