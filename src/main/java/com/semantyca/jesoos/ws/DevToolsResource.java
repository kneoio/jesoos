package com.semantyca.jesoos.ws;

import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import io.quarkus.arc.profile.IfBuildProfile;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/jesoos/dev")
@IfBuildProfile("dev")
public class DevToolsResource {

    @Inject
    PublicChatSessionManager sessionManager;

    @GET
    @Path("/pending-otp")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPendingOtp(@QueryParam("email") String email) {
        if (email == null || email.isBlank()) {
            return Response.status(400)
                    .entity(new JsonObject().put("error", "email required").encode())
                    .build();
        }
        String otp = sessionManager.getPendingOtp(email);
        if (otp == null) {
            return Response.status(404)
                    .entity(new JsonObject().put("found", false).encode())
                    .build();
        }
        return Response.ok(new JsonObject().put("found", true).put("otp", otp).encode()).build();
    }
}
