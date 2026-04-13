package com.semantyca.jesoos.service.agenda;

import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.repository.agenda.AgendaRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class AgendaPersistenceService {
    private static final Logger LOGGER = Logger.getLogger(AgendaPersistenceService.class);

    private final AgendaRepository repository;

    @Inject
    public AgendaPersistenceService(AgendaRepository repository) {
        this.repository = repository;
    }

    public Uni<UUID> persist(StreamAgenda agenda, UUID brandId, long authorId) {
        return repository.save(agenda, brandId, authorId)
                .invoke(id -> LOGGER.infof("Agenda persisted for brand %s, config id: %s", brandId, id))
                .onFailure().invoke(e -> LOGGER.errorf(e, "Failed to persist agenda for brand %s", brandId));
    }
}
