package com.semantyca.jesoos;

import com.semantyca.jesoos.rest.AuthResource;
import com.semantyca.jesoos.rest.ChatUploadResource;
import com.semantyca.jesoos.rest.CommandResource;
import com.semantyca.jesoos.rest.InfoResource;
import com.semantyca.jesoos.rest.SoundFragmentUploadResource;
import com.semantyca.jesoos.ws.PublicChatController;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class JesoosApplication {


    @Inject
    AuthResource authResource;

    @Inject
    CommandResource commandResource;

    @Inject
    InfoResource infoResource;

    @Inject
    PublicChatController publicChatController;

    @Inject
    ChatUploadResource chatUploadResource;

    @Inject
    SoundFragmentUploadResource soundFragmentUploadResource;

    void setupRoutes(@Observes Router router) {
        authResource.setupRoutes(router);
        commandResource.setupRoutes(router);
        infoResource.setupRoutes(router);
        publicChatController.setupRoutes(router);
        chatUploadResource.setupRoutes(router);
        soundFragmentUploadResource.setupRoutes(router);
    }
}
