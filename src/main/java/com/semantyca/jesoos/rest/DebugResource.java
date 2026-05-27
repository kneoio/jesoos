package com.semantyca.jesoos.rest;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class DebugResource extends AbstractResource {
    private static final Logger LOGGER = Logger.getLogger(DebugResource.class);

    @Inject
    BrandService brandService;

    @Inject
    AiAgentService aiAgentService;

    @Inject
    IntroTtsGenerator introTtsGenerator;

    public void setupRoutes(Router router) {
        String path = "/jesoos/debug";
        router.route(HttpMethod.POST, path + "/:brand/instruction")
                .handler(BodyHandler.create())
                .handler(this::handleDebugInstruction);
    }

    private void handleDebugInstruction(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        JsonObject body;
        try {
            body = rc.body().asJsonObject();
        } catch (Exception e) {
            rc.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "invalid JSON body").encode());
            return;
        }

        String instruction = body.getString("instruction");
        if (instruction == null || instruction.isBlank()) {
            rc.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "instruction is required").encode());
            return;
        }

        JsonObject varsJson = body.getJsonObject("contextVars");
        Map<String, Object> contextVars = new HashMap<>();
        if (varsJson != null) {
            for (String key : varsJson.fieldNames()) {
                contextVars.put(key, varsJson.getValue(key));
            }
        }

        String langTag = body.getString("language", "en-US");
        LanguageTag language;
        try {
            language = LanguageTag.fromTag(langTag);
        } catch (Exception e) {
            language = LanguageTag.EN_US;
        }

        LanguageTag finalLanguage = language;

        brandService.getBySlugName(brand)
                .chain(b -> aiAgentService.getById(b.getAiAgentId(), SuperUser.build()))
                .chain(agent -> introTtsGenerator.debugInstruction(instruction, contextVars, agent, finalLanguage))
                .subscribe().with(
                        result -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(result.encode()),
                        failure -> handleCommandFailure(rc, brand, "debug instruction", failure)
                );
    }
}
