package com.semantyca.djinn.rest;

import com.semantyca.djinn.service.stream.BrandPool;
import com.semantyca.djinn.service.stream.StreamAgendaService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandResource {
    
    private static final Logger LOGGER = Logger.getLogger(CommandResource.class);

    @Inject
    StreamAgendaService streamAgendaService;

    @Inject
    BrandPool brandPool;

    public void setupRoutes(Router router) {
        String path = "/command";
        
        router.route(HttpMethod.POST, path + "/:brand/:command").handler(this::handleCommand);
        router.route(HttpMethod.GET, path + "/agendas").handler(this::getAgendas);
    }
    
    private void getAgendas(RoutingContext rc) {
        JsonObject agendasJson = new JsonObject();
        brandPool.getOnlineStationsSnapshot().forEach(stream -> {
            if (stream.getAgenda() != null) {
                String key = stream.getSlugName();
                var agenda = stream.getAgenda();
                JsonObject agendaJson = new JsonObject()
                        .put("key", key)
                        .put("createdAt", agenda.getCreatedAt().toString())
                        .put("totalScenes", agenda.getLiveScenes().size())
                        .put("scenes", agenda.getLiveScenes().stream().map(scene -> new JsonObject()
                                .put("id", scene.getSceneId().toString())
                                .put("title", scene.getSceneTitle())
                                .put("scheduledStartTime", scene.getScheduledStartTime().toString())
                                .put("durationSeconds", scene.getDurationSeconds())
                                .put("totalSongs", scene.getSongs().size())
                        ).collect(java.util.stream.Collectors.toList()));
                agendasJson.put(key, agendaJson);
            }
        });
        rc.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(agendasJson.encode());
    }
    
    private void handleCommand(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String command = rc.pathParam("command").toLowerCase();

        if ("start".equals(command)) {
            handleStartCommand(rc, slugName);
        } else {
            rc.response()
                    .setStatusCode(400)
                    .end(new JsonObject().put("error", "Unknown command: " + command).encode());
        }
    }

    private void handleStartCommand(RoutingContext rc, String brand) {
        String slugName = rc.request().getParam("brand");

        if (slugName == null || slugName.isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .end(new JsonObject().put("error", "Missing brand parameter").encode());
            return;
        }

        streamAgendaService.buildRadioLiveAgenda(slugName)
                .subscribe()
                .with(
                        stream -> {
                            String key = brand + ":" + stream.getAgenda().getTotalScenes();

                            JsonObject response = new JsonObject()
                                    .put("success", true)
                                    .put("key", key)
                                    .put("totalScenes", stream.getAgenda().getTotalScenes())
                                    .put("createdAt", stream.getAgenda().getCreatedAt());

                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                        },
                        failure -> {
                            LOGGER.errorf(failure, "Failed to build agenda for brand: %s", brand);
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject()
                                            .put("error", "Failed to build agenda: " + failure.getMessage())
                                            .encode());
                        }
                );
    }

}
