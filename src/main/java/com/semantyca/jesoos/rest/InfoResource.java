package com.semantyca.jesoos.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.jesoos.dto.agenda.AgendasResponseDTO;
import com.semantyca.jesoos.service.stream.AgendaViewService;
import com.semantyca.jesoos.service.stream.StaggeredSongScheduler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class InfoResource {

    private static final Logger LOGGER = Logger.getLogger(InfoResource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    @Inject
    AgendaViewService agendaViewService;

    @Inject
    StaggeredSongScheduler staggeredSongScheduler;

    public void setupRoutes(Router router) {
        String path = "/jesoos";
        router.route(HttpMethod.GET, path + "/info/brand-timers").handler(this::getBrandTimers);
        router.route(HttpMethod.GET, path + "/agendas").handler(this::getAgendas);
    }

    private void getBrandTimers(RoutingContext rc) {
        try {
            JsonObject result = new JsonObject();
            staggeredSongScheduler.getBrandTimers()
                    .forEach((brand, timers) -> {
                        JsonObject brandTimers = new JsonObject();
                        timers.forEach((seq, timerId) -> brandTimers.put(String.valueOf(seq), timerId));
                        result.put(brand, brandTimers);
                    });
            rc.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
        } catch (Exception e) {
            LOGGER.error("Failed to get brand timers", e);
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", e.getMessage()).encode());
        }
    }


    private void getAgendas(RoutingContext rc) {
        rc.vertx().executeBlocking(() -> {
            AgendasResponseDTO response = agendaViewService.getAllAgendas();
            return OBJECT_MAPPER.writeValueAsString(response);
        }).onSuccess(json -> {
            rc.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(json);
        }).onFailure(err -> {
            LOGGER.error("Failed to get agendas", err);
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", err.getMessage()).encode());
        });
    }
}
