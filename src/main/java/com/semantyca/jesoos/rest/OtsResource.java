package com.semantyca.jesoos.rest;

import com.semantyca.jesoos.service.OneTimeStreamService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OtsResource extends AbstractResource {

    @Inject
    OneTimeStreamService oneTimeStreamService;

    public void setupRoutes(Router router) {
        String path = "/jesoos/ots";
        router.route(HttpMethod.GET, path + "/:slugName").handler(this::handleGet);
        router.route(HttpMethod.DELETE, path + "/:id").handler(this::handleDelete);
    }

    private void handleGet(RoutingContext rc) {
        String slugName = rc.pathParam("slugName");
        oneTimeStreamService.getBySlugName(slugName)
                .subscribe().with(
                        stream -> {
                            if (stream == null) {
                                rc.fail(404);
                            } else {
                                rc.response().setStatusCode(200).putHeader("Content-Type", "application/json")
                                        .end(new JsonObject()
                                                .put("slugName", stream.getSlugName())
                                                .put("id", stream.getStreamId())
                                                .put("status", stream.getStatus().name())
                                                .encode());
                            }
                        },
                        failure -> handleCommandFailure(rc, slugName, "get OTS", failure)
                );
    }

    private void handleDelete(RoutingContext rc) {
        String id = rc.pathParam("id");
        oneTimeStreamService.delete(id)
                .subscribe().with(
                        ignored -> rc.response().setStatusCode(204).end(),
                        failure -> handleCommandFailure(rc, id, "delete OTS", failure)
                );
    }
}
