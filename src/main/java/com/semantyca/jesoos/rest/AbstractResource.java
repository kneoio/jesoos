package com.semantyca.jesoos.rest;

import io.vertx.ext.web.RoutingContext;

public abstract class AbstractResource {

    protected void handleCommandFailure(RoutingContext rc, String brand, String action, Throwable failure) {
        if (failure instanceof IllegalArgumentException) {
            rc.fail(400, failure);
        } else {
            rc.fail(500, new RuntimeException("Failed to " + action + " for brand " + brand + ": " + failure.getMessage(), failure));
        }
    }
}
