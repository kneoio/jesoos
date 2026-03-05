package com.semantyca.djinn;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.semantyca.djinn.rest.DebugResource;

@ApplicationScoped
public class DjinnApplication {

    @Inject
    DebugResource debugResource;

    void setupRoutes(@Observes Router router) {
        debugResource.setupRoutes(router);
    }
}
