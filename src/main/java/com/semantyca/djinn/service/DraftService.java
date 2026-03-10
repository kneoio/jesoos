package com.semantyca.djinn.service;

import com.semantyca.djinn.dto.DraftDTO;
import com.semantyca.djinn.service.draft.DraftRepository;
import com.semantyca.mixpla.model.Draft;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class DraftService extends AbstractService<Draft, DraftDTO> {

    private final DraftRepository repository;

    @Inject
    public DraftService(UserService userService, DraftRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<Draft>> getAll() {
        return repository.getAll(0, 0, false, SuperUser.build(), null);
    }

    public Uni<Draft> getById(UUID id, IUser user) {
        return repository.findById(id, user, true);
    }

}
