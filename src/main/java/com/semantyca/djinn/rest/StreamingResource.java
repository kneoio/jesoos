package com.semantyca.djinn.rest;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StreamingResource {
    
    private static final Logger LOGGER = Logger.getLogger(StreamingResource.class);

    
    public void setupRoutes(Router router) {
        String path = "/stream";
        
        router.route(HttpMethod.GET, path + "/:brand/master.m3u8").handler(this::getMasterPlaylist);

    }
    
    private void getMasterPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        

    }

}
