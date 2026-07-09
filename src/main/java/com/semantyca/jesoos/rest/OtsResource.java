package com.semantyca.jesoos.rest;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.service.OneTimeStreamService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OtsResource extends AbstractResource {
    private static final Logger LOGGER = Logger.getLogger(OtsResource.class);

    @Inject
    OneTimeStreamService oneTimeStreamService;

    public void setupRoutes(Router router) {
        String path = "/jesoos/ots";
        router.route(HttpMethod.POST, path + "/:brand/run").handler(this::handleRun);
        router.route(HttpMethod.GET, path + "/:slugName").handler(this::handleGet);
        router.route(HttpMethod.DELETE, path + "/:id").handler(this::handleDelete);
    }

    private void handleRun(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        JsonObject body = rc.body().asJsonObject();
        if (body == null) {
            rc.fail(400, new IllegalArgumentException("Missing request body"));
            return;
        }
        String scriptIdStr = body.getString("scriptId");
        if (scriptIdStr == null) {
            rc.fail(400, new IllegalArgumentException("Missing scriptId"));
            return;
        }
        UUID scriptId;
        try {
            scriptId = UUID.fromString(scriptIdStr);
        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid scriptId"));
            return;
        }
        Map<String, Object> userVariables = new HashMap<>();
        JsonObject vars = body.getJsonObject("userVariables");
        if (vars != null) {
            vars.forEach(entry -> userVariables.put(entry.getKey(), entry.getValue()));
        }
        oneTimeStreamService.run(brand, scriptId, userVariables, SuperUser.build())
                .subscribe().with(
                        stream -> rc.response().setStatusCode(200).putHeader("Content-Type", "application/json")
                                .end(new JsonObject()
                                        .put("slugName", stream.getSlugName())
                                        .put("id", stream.getStreamId())
                                        .put("status", stream.getStatus().name())
                                        .encode()),
                        failure -> handleCommandFailure(rc, brand, "run OTS", failure)
                );
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
