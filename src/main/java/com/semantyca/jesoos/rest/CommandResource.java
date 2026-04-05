package com.semantyca.jesoos.rest;

import com.semantyca.jesoos.service.CommandService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class CommandResource extends AbstractResource {
    private static final Logger LOGGER = Logger.getLogger(CommandResource.class);

    @Inject
    CommandService commandService;

    public void setupRoutes(Router router) {
        String path = "/jesoos/command";
        router.route(HttpMethod.POST, path + "/:brand/start").handler(this::handleStart);
        router.route(HttpMethod.POST, path + "/:brand/stop").handler(this::handleStop);
        router.route(HttpMethod.POST, path + "/:brand/enable-dj").handler(this::handleEnableDj);
        router.route(HttpMethod.POST, path + "/:brand/disable-dj").handler(this::handleDisableDj);
        router.route(HttpMethod.POST, path + "/:brand/emit-timeline-entry/:sceneId/:sequenceNumber").handler(this::handleEmitTimelineEntry);
    }

    private void handleStart(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        rc.vertx().executeBlocking(() -> handleStartCommand(slugName))
                .onSuccess(response -> {
                    rc.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json")
                            .end(response.encode());
                })
                .onFailure(failure -> handleCommandFailure(rc, slugName, "start", failure));
    }

    private void handleStop(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        rc.vertx().executeBlocking(() -> handleStopCommand(slugName))
                .onSuccess(response -> {
                    rc.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json")
                            .end(response.encode());
                })
                .onFailure(failure -> handleCommandFailure(rc, slugName, "stop", failure));
    }

    private void handleEnableDj(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        rc.vertx().executeBlocking(() -> handleEnableDjCommand(slugName))
                .onSuccess(response -> {
                    rc.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json")
                            .end(response.encode());
                })
                .onFailure(failure -> handleCommandFailure(rc, slugName, "enable-dj", failure));
    }

    private void handleDisableDj(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        rc.vertx().executeBlocking(() -> handleDisableDjCommand(slugName))
                .onSuccess(response -> {
                    rc.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json")
                            .end(response.encode());
                })
                .onFailure(failure -> handleCommandFailure(rc, slugName, "disable-dj", failure));
    }

    private void handleEmitTimelineEntry(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String sceneIdParam = rc.pathParam("sceneId");
        String seqNumParam = rc.pathParam("sequenceNumber");
        
        try {
            UUID sceneId = UUID.fromString(sceneIdParam);
            int sequenceNumber = Integer.parseInt(seqNumParam);
            rc.vertx().executeBlocking(() -> handleEmitTimelineEntryCommand(slugName, sceneId, sequenceNumber))
                    .onSuccess(response -> {
                        rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode());
                    })
                    .onFailure(failure -> handleCommandFailure(rc, slugName, "emit-timeline-entry", failure));
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("error", "Invalid sceneId or sequence number: " + e.getMessage())
                            .encode());
        }
    }

    private JsonObject handleStartCommand(String brand) {
        try {
            return commandService.startBrand(brand)
                    .await().indefinitely();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonObject handleStopCommand(String brand) {
        try {
            JsonObject response = commandService.stopBrand(brand)
                    .await().indefinitely();
            LOGGER.infof("Stop command executed for brand: %s", brand);
            return response;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonObject handleEnableDjCommand(String brand) {
        try {
            JsonObject response = commandService.enableDj(brand)
                    .await().indefinitely();
            LOGGER.infof("DJ enabled via command for brand: %s", brand);
            return response;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonObject handleDisableDjCommand(String brand) {
        try {
            JsonObject response = commandService.disableDj(brand)
                    .await().indefinitely();
            LOGGER.infof("DJ disabled via command for brand: %s", brand);
            return response;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonObject handleEmitTimelineEntryCommand(String brand, UUID sceneId, int sequenceNumber) {
        try {
            JsonObject response = commandService.emitTimelineEntry(brand, sceneId, sequenceNumber)
                    .await().indefinitely();
            LOGGER.infof("Timeline entry #%d from scene %s emitted via command for brand: %s", sequenceNumber, sceneId, brand);
            return response;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
