package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.PlaylistQueueService;
import com.semantyca.jesoos.service.agenda.AgendaViewService;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

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
                if (agenda.getScenes() != null) {
                    for (var scene : agenda.getScenes()) {
                        if (scene.getTimeline() == null) continue;
                        for (var entry : scene.getTimeline()) {
                            if (entry.getSongs() == null) continue;
                            for (var song : entry.getSongs()) {
                                entries.add(new JsonObject()
                                        .put("time", entry.getScheduledEmissionTime() != null
                                                ? entry.getScheduledEmissionTime().toString() : null)
                                        .put("artist", song.getArtist())
                                        .put("title", song.getSongTitle())
                                        .put("status", entry.getStatus()));
                            }
                        }
                    }
                }
                return ToolNodeResult.ok(new JsonObject().put("ok", true).put("agenda", entries).encode());
            }).onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
            default -> playlistQueueService.getQueueByBrandSlug(brandName)
                    .map(queue -> ToolNodeResult.ok(new JsonObject().put("ok", true).put("queue", queue).encode()))
                    .onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
        };
    }
}
