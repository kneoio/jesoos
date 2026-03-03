package com.semantyca.djinn;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.semantyca.djinn.rest.StreamingResource;

@ApplicationScoped
public class DjinnApplication {

    @Inject
    StreamingResource streamingResource;
    


    void setupRoutes(@Observes Router router) {
        streamingResource.setupRoutes(router);
    }
}
