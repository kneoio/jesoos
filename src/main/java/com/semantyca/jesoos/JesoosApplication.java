package com.semantyca.jesoos;

import com.semantyca.jesoos.rest.CommandResource;
import com.semantyca.jesoos.rest.PublicChatController;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class JesoosApplication {


    @Inject
    CommandResource commandResource;

    @Inject
    PublicChatController publicChatController;

    void setupRoutes(@Observes Router router) {
        commandResource.setupRoutes(router);
        publicChatController.setupRoutes(router);
    }
}
