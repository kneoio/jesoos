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
        String traceHeader = rc.request().getHeader("X-Trace-Id");
        UUID traceId = traceHeader != null ? UUID.fromString(traceHeader) : UUID.randomUUID();
        commandService.startBrand(slugName, traceId)
                .subscribe().with(
                        response -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .putHeader("X-Trace-Id", traceId.toString())
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, slugName, "start", failure)
                );
    }

    private void handleStop(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String traceHeader = rc.request().getHeader("X-Trace-Id");
        UUID traceId = traceHeader != null ? UUID.fromString(traceHeader) : UUID.randomUUID();
        commandService.stopBrand(slugName, traceId)
                .subscribe().with(
                        response -> rc.response().setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .putHeader("X-Trace-Id", traceId.toString())
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, slugName, "stop", failure)
                );
    }

    private void handleEnableDj(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String traceHeader = rc.request().getHeader("X-Trace-Id");
        UUID traceId = traceHeader != null ? UUID.fromString(traceHeader) : UUID.randomUUID();
        commandService.enableDj(slugName, traceId)
                .subscribe().with(
                        response -> rc.response().setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .putHeader("X-Trace-Id", traceId.toString())
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, slugName, "enable-dj", failure)
                );
    }

    private void handleDisableDj(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String traceHeader = rc.request().getHeader("X-Trace-Id");
        UUID traceId = traceHeader != null ? UUID.fromString(traceHeader) : UUID.randomUUID();
        commandService.disableDj(slugName, traceId)
                .subscribe().with(
                        response -> rc.response().setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .putHeader("X-Trace-Id", traceId.toString())
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, slugName, "disable-dj", failure)
                );
    }

    private void handleBackpressure(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String traceHeader = rc.request().getHeader("X-Trace-Id");
        UUID traceId = traceHeader != null ? UUID.fromString(traceHeader) : UUID.randomUUID();
        commandService.backpressure(slugName, traceId)
                .subscribe().with(
                        response -> rc.response().setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .putHeader("X-Trace-Id", traceId.toString())
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, slugName, "backpressure", failure));
    }

    private void handleEmitTimelineEntry(RoutingContext rc) {
        String slugName = rc.pathParam("brand").toLowerCase();
        String sceneIdParam = rc.pathParam("sceneId");
        String seqNumParam = rc.pathParam("sequenceNumber");
        String traceHeader = rc.request().getHeader("X-Trace-Id");
        UUID traceId = traceHeader != null ? UUID.fromString(traceHeader) : UUID.randomUUID();

        try {
            UUID sceneId = UUID.fromString(sceneIdParam);
            int sequenceNumber = Integer.parseInt(seqNumParam);
            commandService.emitTimelineEntry(slugName, sceneId, sequenceNumber, traceId)
                    .subscribe().with(
                            response -> rc.response().setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .putHeader("X-Trace-Id", traceId.toString())
                                    .end(response.encode()),
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
        String traceHeader = rc.request().getHeader("X-Trace-Id");
        UUID traceId = traceHeader != null ? UUID.fromString(traceHeader) : UUID.randomUUID();
        commandService.startOts(otsSlug, traceId)
                .subscribe().with(
                        response -> rc.response().setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .putHeader("X-Trace-Id", traceId.toString())
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, otsSlug, "ots-start", failure)
                );
    }

    private void handleOtsStop(RoutingContext rc) {
        String otsSlug = rc.pathParam("otsSlug");
        String traceHeader = rc.request().getHeader("X-Trace-Id");
        UUID traceId = traceHeader != null ? UUID.fromString(traceHeader) : UUID.randomUUID();
        commandService.stopOts(otsSlug, traceId)
                .subscribe().with(
                        response -> rc.response().setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .putHeader("X-Trace-Id", traceId.toString())
                                .end(response.encode()),
                        failure -> handleCommandFailure(rc, otsSlug, "ots-stop", failure)
                );
    }

}
