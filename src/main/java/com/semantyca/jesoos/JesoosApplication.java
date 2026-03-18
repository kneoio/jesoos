package com.semantyca.jesoos;

import com.semantyca.jesoos.rest.CommandResource;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class JesoosApplication {


    @Inject
    CommandResource commandResource;

    void setupRoutes(@Observes Router router) {
        commandResource.setupRoutes(router);

    }
}
