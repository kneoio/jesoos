package com.semantyca.jesoos.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.jesoos.dto.agenda.AgendasResponseDTO;
import com.semantyca.jesoos.service.CommandService;
import com.semantyca.jesoos.service.stream.AgendaViewService;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Inject
    AgendaViewService agendaViewService;

    @Inject
    CommandService commandService;

    public void setupRoutes(Router router) {
        String path = "/jesoos";
        
        router.route(HttpMethod.GET, path + "/:brand/dj-status").handler(this::getDjStatus);
        router.route(HttpMethod.POST, path + "/stop-all").handler(this::handleStopAllCommand);
        router.route(HttpMethod.GET, path + "/agendas").handler(this::getAgendas);
        router.route(HttpMethod.POST, path + "/:brand/:command").handler(this::handleCommand);
        
        LOGGER.infof("Registered Vert.x routes: GET %s/:brand/dj-status, POST %s/stop-all, GET %s/agendas, POST %s/:brand/:command", 
                path, path, path, path);
    }
    
    private void getAgendas(RoutingContext rc) {
        rc.vertx().executeBlocking(() -> {
            AgendasResponseDTO response = agendaViewService.getAllAgendas();
            return OBJECT_MAPPER.writeValueAsString(response);
        }).onSuccess(json -> {
            rc.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(json);
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

    private void handleStopAllCommand(RoutingContext rc) {
        commandService.stopAllBrands()
                .subscribe()
                .with(
                        response -> {
                            LOGGER.infof("Stop-all command executed");
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                        },
                        failure -> {
                            LOGGER.error("Failed to execute stop-all command", failure);
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject()
                                            .put("error", "Failed to stop all brands: " + failure.getMessage())
                                            .encode());
                        }
                );
    }

    private void getDjStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        commandService.getDjStatus(brand)
                .subscribe()
                .with(
                        djEnabled -> {
                            LOGGER.infof("DJ status checked for brand: %s - %s", brand, djEnabled);
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(String.valueOf(djEnabled));
                        },
                        failure -> handleCommandFailure(rc, brand, "get DJ status", failure)
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
