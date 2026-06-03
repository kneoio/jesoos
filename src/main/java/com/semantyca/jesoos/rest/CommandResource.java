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
        router.route(HttpMethod.POST, path + "/:brand/backpressure").handler(this::handleBackpressure);
        router.route(HttpMethod.POST, path + "/ots/:otsSlug/start").handler(this::handleOtsStart);
        router.route(HttpMethod.POST, path + "/ots/:otsSlug/stop").handler(this::handleOtsStop);
    }

    private void handleStart(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        commandService.startBrand(slugName)
                .subscribe().with(
                        response -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, slugName, "start", failure)
                );
    }

    private void handleStop(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        commandService.stopBrand(slugName)
                .subscribe().with(
                        response -> {
                            LOGGER.infof("Stop command executed for brand: %s", slugName);
                            rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(response.encode());
                        },
                        failure -> handleCommandFailure(rc, slugName, "stop", failure)
                );
    }

    private void handleEnableDj(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        commandService.enableDj(slugName)
                .subscribe().with(
                        response -> {
                            LOGGER.infof("DJ enabled via command for brand: %s", slugName);
                            rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(response.encode());
                        },
                        failure -> handleCommandFailure(rc, slugName, "enable-dj", failure)
                );
    }

    private void handleDisableDj(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        commandService.disableDj(slugName)
                .subscribe().with(
                        response -> {
                            LOGGER.infof("DJ disabled via command for brand: %s", slugName);
                            rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(response.encode());
                        },
                        failure -> handleCommandFailure(rc, slugName, "disable-dj", failure)
                );
    }

    private void handleBackpressure(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        commandService.backpressure(slugName)
                .subscribe().with(
                        response -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, slugName, "backpressure", failure));
    }

    private void handleEmitTimelineEntry(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String sceneIdParam = rc.pathParam("sceneId");
        String seqNumParam = rc.pathParam("sequenceNumber");

        try {
            UUID sceneId = UUID.fromString(sceneIdParam);
            int sequenceNumber = Integer.parseInt(seqNumParam);
            commandService.emitTimelineEntry(slugName, sceneId, sequenceNumber)
                    .subscribe().with(
                            response -> {
                                LOGGER.infof("Timeline entry #%d from scene %s emitted via command for brand: %s", sequenceNumber, sceneId, slugName);
                                rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(response.encode());
                            },
                            failure -> handleCommandFailure(rc, slugName, "emit-timeline-entry", failure)
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("error", "Invalid sceneId or sequence number: " + e.getMessage())
                            .encode());
        }
    }


    private void handleOtsStart(RoutingContext rc) {
        String otsSlug = rc.pathParam("otsSlug");
        commandService.startOts(otsSlug)
                .subscribe().with(
                        response -> rc.response().setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, otsSlug, "ots-start", failure)
                );
    }

    private void handleOtsStop(RoutingContext rc) {
        String otsSlug = rc.pathParam("otsSlug");
        commandService.stopOts(otsSlug)
                .subscribe().with(
                        response -> rc.response().setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, otsSlug, "ots-stop", failure)
                );
    }

}
