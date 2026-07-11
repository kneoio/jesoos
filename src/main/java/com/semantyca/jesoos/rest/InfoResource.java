package com.semantyca.jesoos.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.service.CommandService;
import com.semantyca.jesoos.service.agenda.AgendaViewService;
import com.semantyca.jesoos.service.live.OneTimeStreamPool;
import com.semantyca.jesoos.service.live.ScenePool;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class InfoResource extends AbstractResource {

    private static final Logger LOGGER = Logger.getLogger(InfoResource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    @Inject
    AgendaViewService agendaViewService;

    @Inject
    CommandService commandService;

    @Inject
    ScenePool scenePool;

    @Inject
    OneTimeStreamPool oneTimeStreamPool;

    @Inject
    InternalRestCall internalRestCall;

    public void setupRoutes(Router router) {
        String path = "/jesoos/info";
        router.route(HttpMethod.GET, path + "/:brand/live").handler(this::getLiveStatus);
        router.route(HttpMethod.GET, path + "/:brand/dj-status").handler(this::getDjStatus);
        router.route(HttpMethod.GET, path + "/:brand/agendas").handler(this::getAgendas);
        router.route(HttpMethod.GET, path + "/queue/:brand").handler(this::validateMixplaAccess).handler(this::getQueueState);
    }

    private void validateMixplaAccess(RoutingContext rc) {
        // TODO: validate Mixpla access credentials
        rc.next();
    }

    private void getQueueState(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        internalRestCall.getQueueFromAivox(brand)
                .subscribe().with(
                        queue -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject().put("ok", true).put("queue", queue).encode()),
                        err -> handleCommandFailure(rc, brand, "get queue state", err)
                );
    }

    private void getLiveStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        LiveScene activeScene = scenePool.getActiveScene(brand);
        if (activeScene != null) {
            rc.vertx().executeBlocking(() -> OBJECT_MAPPER.writeValueAsString(activeScene))
                    .onSuccess(json -> rc.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json")
                            .end(json))
                    .onFailure(err -> rc.fail(500, new RuntimeException("Failed to serialize live status for brand " + brand + ": " + err.getMessage(), err)));
            return;
        }

        // No radio scene ticking for this slug -- OTS doesn't go through ScenePool (it emits
        // sequentially, not on the AgendaTicker's wall-clock schedule), so report its own
        // pool status instead of just "live": false.
        oneTimeStreamPool.get(brand)
                .subscribe().with(
                        ots -> {
                            JsonObject json = ots == null
                                    ? new JsonObject().put("live", false)
                                    : new JsonObject()
                                            .put("live", ots.getStatus() == StreamStatus.WARMING_UP || ots.getStatus() == StreamStatus.ON_LINE)
                                            .put("ots", true)
                                            .put("status", ots.getStatus().name())
                                            .put("slugName", ots.getSlugName());
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(json.encode());
                        },
                        err -> rc.fail(500, new RuntimeException("Failed to fetch live status for " + brand + ": " + err.getMessage(), err))
                );
    }

    private void getAgendas(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        agendaViewService.getAgendaByBrandAsync(brand)
                .chain(agenda -> {
                    if (agenda == null) return Uni.createFrom().nullItem();
                    try {
                        return Uni.createFrom().item(OBJECT_MAPPER.writeValueAsString(agenda));
                    } catch (Exception e) {
                        return Uni.createFrom().failure(e);
                    }
                })
                .subscribe().with(
                        json -> {
                            if (json == null) {
                                rc.fail(404, new IllegalArgumentException("Agenda not found for brand: " + brand));
                                return;
                            }
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(json);
                        },
                        err -> rc.fail(500, new RuntimeException("Failed to serialize agenda for brand " + brand + ": " + err.getMessage(), err))
                );
    }

    private void getDjStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        // DJ on/off is a per-brand toggle (CommandService.enableDj/disableDj); an OTS's own slug
        // never has one set. For a brand-scoped OTS, report the master brand's toggle instead of
        // always "false" -- matches SongEmitter/JingleSongEmitter's djBrandSlug resolution.
        resolveDjBrandSlug(brand)
                .chain(commandService::getDjStatus)
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

    private Uni<String> resolveDjBrandSlug(String slug) {
        return oneTimeStreamPool.get(slug)
                .map(ots -> (ots != null && ots.getMasterBrand() != null) ? ots.getMasterBrand().getSlugName() : slug);
    }

}
