package com.semantyca.djinn;

import com.semantyca.djinn.rest.CommandResource;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.semantyca.djinn.rest.DebugResource;

@ApplicationScoped
public class DjinnApplication {

    @Inject
    DebugResource debugResource;

    @Inject
    CommandResource commandResource;

    void setupRoutes(@Observes Router router) {
        commandResource.setupRoutes(router);
        debugResource.setupRoutes(router);
    }
}
