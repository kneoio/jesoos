package com.semantyca.jesoos.rest;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.model.stream.RadioStream;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.scripting.DraftFactory;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
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

    @Inject
    DraftFactory draftFactory;

    public void setupRoutes(Router router) {
        String path = "/jesoos/debug";
        router.route(HttpMethod.POST, path + "/:brand/instruction")
                .handler(BodyHandler.create())
                .handler(this::handleDebugInstruction);
        router.route(HttpMethod.POST, path + "/:brand/draft")
                .handler(BodyHandler.create())
                .handler(this::handleDebugDraft);
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
                .chain(b -> aiAgentService.getById(b.getAiAgentId()))
                .chain(agent -> introTtsGenerator.debugInstruction(instruction, contextVars, agent, finalLanguage))
                .subscribe().with(
                        result -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(result.encode()),
                        failure -> handleCommandFailure(rc, brand, "debug instruction", failure)
                );
    }

    private void handleDebugDraft(RoutingContext rc) {
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

        String code = body.getString("code");
        if (code == null || code.isBlank()) {
            rc.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "code is required").encode());
            return;
        }

        String finalCode = code;

        String songTitle = body.getString("songTitle");
        String songArtist = body.getString("songArtist");
        String songDescription = body.getString("songDescription");
        String sharerName = body.getString("sharerName");

        SoundFragment song = null;
        if (songTitle != null || songArtist != null || songDescription != null) {
            song = new SoundFragment();
            song.setTitle(songTitle != null ? songTitle : "");
            song.setArtist(songArtist != null ? songArtist : "");
            song.setDescription(songDescription != null ? songDescription : "");
        }
        SoundFragment finalSong = song;
        String finalSharerName = sharerName;

        brandService.getBySlugName(brand)
                .flatMap(b -> aiAgentService.getById(b.getAiAgentId())
                        .flatMap(agent -> {
                            RadioStream stream = new RadioStream(b);
                            return draftFactory.renderCode(finalCode, finalSong, agent, stream, finalSharerName);
                        }))
                .subscribe().with(
                        draft -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject().put("draft", draft).encode()),
                        failure -> handleCommandFailure(rc, brand, "debug draft", failure)
                );
    }
}
