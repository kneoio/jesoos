package com.semantyca.djinn.rest;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandResource {
    
    private static final Logger LOGGER = Logger.getLogger(CommandResource.class);

    public void setupRoutes(Router router) {
        String path = "/command";
        
        router.route(HttpMethod.POST, path + "/:brand/:command").handler(this::getMasterPlaylist);

    }
    
    private void getMasterPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        String command = rc.pathParam("command").toLowerCase();


    }

}
