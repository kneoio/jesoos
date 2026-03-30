package com.semantyca.jesoos.rest;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

public abstract class AbstractResource {
    private static final Logger LOGGER = Logger.getLogger(AbstractResource.class);

    protected void handleCommandFailure(RoutingContext rc, String brand, String action, Throwable failure) {
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
