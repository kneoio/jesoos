package com.semantyca.jesoos.service;

import com.semantyca.jesoos.model.stream.ILiveAgenda;
import com.semantyca.jesoos.service.stream.StreamAgendaService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandService {

    private static final Logger LOGGER = Logger.getLogger(CommandService.class);

    private final StreamAgendaService streamAgendaService;

    @Inject
    public CommandService(StreamAgendaService streamAgendaService) {
        this.streamAgendaService = streamAgendaService;
    }

    public Uni<JsonObject> startBrand(String brand) {
        String slug = brand != null ? brand.trim().toLowerCase() : null;
        if (slug == null || slug.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }

        return streamAgendaService.buildRadioLiveAgenda(slug)
                .map(this::toResponse)
                .invoke(response -> LOGGER.infof("Built agenda for brand %s", slug));
    }

    private JsonObject toResponse(ILiveAgenda agendaHolder) {
        if (agendaHolder == null || agendaHolder.getAgenda() == null) {
            throw new IllegalStateException("Agenda was not created");
        }

        var agenda = agendaHolder.getAgenda();
        String key = agendaHolder.getSlugName() + ":" + agenda.getTotalScenes();
        return new JsonObject()
                .put("success", true)
                .put("key", key)
                .put("totalScenes", agenda.getTotalScenes())
                .put("createdAt", agenda.getCreatedAt());
    }
}
