package com.semantyca.jesoos.service;

import com.semantyca.jesoos.model.stream.ILiveAgenda;
import com.semantyca.jesoos.service.stream.DjStateService;
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
    DjStateService djStateService;

    @Inject
    public CommandService(StreamAgendaService streamAgendaService) {
        this.streamAgendaService = streamAgendaService;
    }

    public Uni<JsonObject> startBrand(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }

        return streamAgendaService.buildRadioLiveAgenda(brand)
                .map(this::toResponse)
                .invoke(response -> LOGGER.infof("Built agenda for brand %s", brand));
    }

    public Uni<JsonObject> enableDj(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        
        return Uni.createFrom().item(() -> {
            djStateService.enableDj(brand);
            LOGGER.infof("DJ enabled for brand: %s (listeners detected)", brand);
            return new JsonObject()
                    .put("success", true)
                    .put("brand", brand)
                    .put("djEnabled", true)
                    .put("message", "DJ intros will be generated");
        });
    }

    public Uni<JsonObject> disableDj(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        
        return Uni.createFrom().item(() -> {
            djStateService.disableDj(brand);
            LOGGER.infof("DJ disabled for brand: %s (no listeners, saving TTS costs)", brand);
            return new JsonObject()
                    .put("success", true)
                    .put("brand", brand)
                    .put("djEnabled", false)
                    .put("message", "DJ intros disabled, songs only mode");
        });
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
