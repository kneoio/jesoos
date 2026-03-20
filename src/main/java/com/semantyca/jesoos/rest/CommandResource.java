package com.semantyca.jesoos.rest;

import com.semantyca.jesoos.service.CommandService;
import com.semantyca.jesoos.service.stream.BrandPool;
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
    BrandPool brandPool;

    @Inject
    CommandService commandService;

    public void setupRoutes(Router router) {
        String path = "/jesoos";
        
        router.route(HttpMethod.POST, path + "/:brand/:command").handler(this::handleCommand);
        router.route(HttpMethod.GET, path + "/agendas").handler(this::getAgendas);
    }
    
    private void getAgendas(RoutingContext rc) {
        rc.vertx().executeBlocking(() -> {
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
            return agendasJson.encode();
        }).onSuccess(encodedJson -> {
            rc.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(encodedJson);
        }).onFailure(err -> {
            LOGGER.error("Failed to get agendas", err);
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", err.getMessage()).encode());
        });
    }
    
    private void handleCommand(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String command = rc.pathParam("command").toLowerCase();

        switch (command) {
            case "start" -> handleStartCommand(rc, slugName);
            case "stop" -> handleStopCommand(rc, slugName);
            case "enabledj" -> handleEnableDjCommand(rc, slugName);
            case "disabledj" -> handleDisableDjCommand(rc, slugName);
            default -> rc.response()
                    .setStatusCode(400)
                    .end(new JsonObject().put("error", "Unknown command: " + command).encode());
        }
    }

    private void handleStartCommand(RoutingContext rc, String brand) {
        commandService.startBrand(brand)
                .subscribe()
                .with(
                        response -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, brand, "build agenda", failure)
                );
    }

    private void handleStopCommand(RoutingContext rc, String brand) {
        commandService.stopBrand(brand)
                .subscribe()
                .with(
                        response -> {
                            LOGGER.infof("Stop command executed for brand: %s", brand);
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                        },
                        failure -> handleCommandFailure(rc, brand, "stop brand", failure)
                );
    }

    private void handleEnableDjCommand(RoutingContext rc, String brand) {
        commandService.enableDj(brand)
                .subscribe()
                .with(
                        response -> {
                            LOGGER.infof("DJ enabled via command for brand: %s", brand);
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                        },
                        failure -> handleCommandFailure(rc, brand, "enable DJ", failure)
                );
    }

    private void handleDisableDjCommand(RoutingContext rc, String brand) {
        commandService.disableDj(brand)
                .subscribe()
                .with(
                        response -> {
                            LOGGER.infof("DJ disabled via command for brand: %s", brand);
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                        },
                        failure -> handleCommandFailure(rc, brand, "disable DJ", failure)
                );
    }

    private void handleCommandFailure(RoutingContext rc, String brand, String action, Throwable failure) {
        if (failure instanceof IllegalArgumentException) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", failure.getMessage()).encode());
        } else {
            LOGGER.errorf(failure, "Failed to %s for brand: %s", action, brand);
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("error", "Failed to " + action + ": " + failure.getMessage())
                            .encode());
        }
    }

}
