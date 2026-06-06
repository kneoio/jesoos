package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.PlaylistQueueService;
import com.semantyca.jesoos.service.agenda.AgendaViewService;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class StreamInfoToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(StreamInfoToolHandler.class);

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            PlaylistQueueService playlistQueueService,
            AgendaViewService agendaViewService,
            String brandName
    ) {
        String action = (String) inputMap.getOrDefault("action", "get_current_scene");
        LOGGER.infof("[StreamInfo] action=%s, brand=%s", action, brandName);

        return switch (action) {
            case "get_today_agenda" -> Uni.createFrom().item(() -> {
                var agenda = agendaViewService.getAgendaByBrand(brandName);
                if (agenda == null) {
                    return ToolNodeResult.ok(new JsonObject().put("ok", false).put("error", "No agenda available").encode());
                }
                var entries = new io.vertx.core.json.JsonArray();
                var formatted = new StringBuilder();
                DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
                if (agenda.getScenes() != null) {
                    for (var scene : agenda.getScenes()) {
                        if (scene.getTimeline() == null) continue;
                        for (var entry : scene.getTimeline()) {
                            if (entry.getSongs() == null) continue;
                            String timeStr = entry.getScheduledEmissionTime() != null
                                    ? entry.getScheduledEmissionTime().format(timeFmt) : "--:--";
                            String status = entry.getStatus() != null ? entry.getStatus().toString() : "";
                            String statusColor = switch (status) {
                                case "COMPLETED" -> "#27ae60";
                                case "EMITTING"  -> "#2980b9";
                                case "PENDING"   -> "#7f8c8d";
                                case "SKIPPED"   -> "#e67e22";
                                case "FAILED"    -> "#c0392b";
                                default          -> "#95a5a6";
                            };
                            for (var song : entry.getSongs()) {
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
                        + "\n\n—\nDiscover more stations at https://mixpla.online";
                return ToolNodeResult.ok(new JsonObject()
                        .put("ok", true)
                        .put("agenda", entries)
                        .put("formatted", formattedText)
                        .encode());
            }).onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
            default -> playlistQueueService.getQueueByBrandSlug(brandName)
                    .map(queue -> ToolNodeResult.ok(new JsonObject().put("ok", true).put("queue", queue).encode()))
                    .onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
        };
    }
}
