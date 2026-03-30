package com.semantyca.jesoos.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.jesoos.dto.agenda.AgendasResponseDTO;
import com.semantyca.jesoos.service.CommandService;
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
public class InfoResource extends AbstractResource {

    private static final Logger LOGGER = Logger.getLogger(InfoResource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    @Inject
    AgendaViewService agendaViewService;

    @Inject
    CommandService commandService;

    @Inject
    StaggeredSongScheduler staggeredSongScheduler;

    public void setupRoutes(Router router) {
        String path = "/jesoos/info";
        router.route(HttpMethod.GET, path + "/:brand/dj-status").handler(this::getDjStatus);
        router.route(HttpMethod.GET, path + "/:brand/agendas").handler(this::getAgendas);
    }

    private void getAgendas(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        rc.vertx().executeBlocking(() -> {
            var agenda = agendaViewService.getAgendaByBrand(brand);
            if (agenda == null) {
                throw new IllegalArgumentException("Agenda not found for brand: " + brand);
            }
            return OBJECT_MAPPER.writeValueAsString(agenda);
        }).onSuccess(json -> {
            rc.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(json);
        }).onFailure(err -> {
            LOGGER.error("Failed to get agenda for brand: " + brand, err);
            int statusCode = err instanceof IllegalArgumentException ? 404 : 500;
            rc.response()
                    .setStatusCode(statusCode)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", err.getMessage()).encode());
        });
    }

    private void getDjStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        commandService.getDjStatus(brand)
                .subscribe()
                .with(
                        djEnabled -> {
                            LOGGER.infof("DJ status checked for brand: %s - %s", brand, djEnabled);
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(String.valueOf(djEnabled));
                        },
                        failure -> handleCommandFailure(rc, brand, "get DJ status", failure)
                );
    }

}
