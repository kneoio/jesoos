package com.semantyca.jesoos.rest;

import com.semantyca.jesoos.external.KeycloakAuthResult;
import com.semantyca.jesoos.external.KeycloakAuthService;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AuthResource extends AbstractResource {
    private static final Logger LOGGER = Logger.getLogger(AuthResource.class);

    @Inject
    KeycloakAuthService keycloakAuthService;

    public void setupRoutes(Router router) {
        String path = "/auth";

        router.post(path + "/start")
                .handler(ctx -> {
                    String email = ctx.request().getFormAttribute("email");
                    if (email == null || email.isBlank()) {
                        ctx.response().setStatusCode(400).end("email is required");
                        return;
                    }
                    keycloakAuthService.startAuth(email)
                            .subscribe().with(
                                    ok -> ctx.response()
                                            .setStatusCode(ok ? 200 : 500)
                                            .end(ok ? "Verification code sent" : "Failed to send code"),
                                    err -> {
                                        LOGGER.error("startAuth failed", err);
                                        ctx.fail(500, err);
                                    }
                            );
                });

        router.post(path + "/verify")
                .handler(ctx -> {
                    String email = ctx.request().getFormAttribute("email");
                    String code = ctx.request().getFormAttribute("code");
                    if (email == null || code == null) {
                        ctx.response().setStatusCode(400).end("email and code are required");
                        return;
                    }
                    Uni<KeycloakAuthResult> result = keycloakAuthService.verifyAuth(email, code);
                    result.subscribe().with(
                            r -> ctx.response()
                                    .setStatusCode(r.success() ? 200 : 401)
                                    .end(r.message()),
                            err -> {
                                LOGGER.error("verifyAuth failed", err);
                                ctx.fail(500, err);
                            }
                    );
                });
    }
}
