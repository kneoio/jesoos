package com.semantyca.jesoos;

import com.semantyca.jesoos.rest.CommandResource;
import com.semantyca.jesoos.rest.InfoResource;
import com.semantyca.jesoos.ws.PublicChatController;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class JesoosApplication {


    @Inject
    CommandResource commandResource;

    @Inject
    InfoResource infoResource;

    @Inject
    PublicChatController publicChatController;

    void setupRoutes(@Observes Router router) {
        commandResource.setupRoutes(router);
        infoResource.setupRoutes(router);
        publicChatController.setupRoutes(router);
    }
}
