package com.semantyca.jesoos;

import com.semantyca.jesoos.rest.AuthResource;
import com.semantyca.jesoos.rest.ChatUploadResource;
import com.semantyca.jesoos.rest.CommandResource;
import com.semantyca.jesoos.rest.OtsResource;
import com.semantyca.jesoos.rest.DebugResource;
import com.semantyca.jesoos.rest.InfoResource;
import com.semantyca.jesoos.rest.SoundFragmentUploadResource;
import com.semantyca.jesoos.ws.AskChatController;
import com.semantyca.jesoos.ws.HelpChatController;
import com.semantyca.jesoos.ws.PublicChatController;
import com.semantyca.core.server.security.GlobalErrorHandler;
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
    OtsResource otsResource;

    @Inject
    InfoResource infoResource;

    @Inject
    PublicChatController publicChatController;

    @Inject
    AskChatController askChatController;

    @Inject
    HelpChatController helpChatController;

    @Inject
    ChatUploadResource chatUploadResource;

    @Inject
    SoundFragmentUploadResource soundFragmentUploadResource;

    @Inject
    DebugResource debugResource;

    void setupRoutes(@Observes Router router) {
        authResource.setupRoutes(router);
        commandResource.setupRoutes(router);
        otsResource.setupRoutes(router);
        infoResource.setupRoutes(router);
        publicChatController.setupRoutes(router);
        askChatController.setupRoutes(router);
        helpChatController.setupRoutes(router);
        chatUploadResource.setupRoutes(router);
        soundFragmentUploadResource.setupRoutes(router);
        debugResource.setupRoutes(router);
        router.errorHandler(500, new GlobalErrorHandler());
        router.errorHandler(400, new GlobalErrorHandler());
        router.errorHandler(401, new GlobalErrorHandler());
        router.errorHandler(403, new GlobalErrorHandler());
        router.errorHandler(404, new GlobalErrorHandler());
    }
}
