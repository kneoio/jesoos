package com.semantyca.jesoos.service;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.ScriptDTO;
import com.semantyca.jesoos.repository.ScriptRepository;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.filter.ScriptFilter;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ScriptService extends AbstractService<Script, ScriptDTO> {
    private final ScriptRepository repository;

    protected ScriptService() {
        super();
        this.repository = null;
    }

    @Inject
    public ScriptService(UserService userService, ScriptRepository repository) {
        super(userService);
        this.repository = repository;
    }
    public Uni<List<Script>> getAll(final int limit, final int offset, ScriptFilter filter) {
        assert repository != null;
        return repository.getAll(limit, offset, false, SuperUser.build(), filter);
    }

    public Uni<Script> getById(UUID id, IUser user) {
        assert repository != null;
        return repository.findById(id, user, false);
    }
}
