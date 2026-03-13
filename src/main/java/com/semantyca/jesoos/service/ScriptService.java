package com.semantyca.jesoos.service;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.ScriptDTO;
import com.semantyca.jesoos.repository.ScriptRepository;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.filter.ScriptFilter;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    private ScriptFilter toFilter(ScriptFilter dto) {
        if (dto == null) {
            return null;
        }

        ScriptFilter filter = new ScriptFilter();
        filter.setActivated(dto.isActivated());
        filter.setLabels(dto.getLabels());
        filter.setTimingMode(dto.getTimingMode());
        filter.setLanguageTag(dto.getLanguageTag());
        filter.setSearchTerm(dto.getSearchTerm());

        return filter;
    }

    public Uni<Integer> getAllCount(final IUser user) {
        return getAllCount(user, null);
    }

    public Uni<Integer> getAllCount(final IUser user, final ScriptFilter scriptFilter) {
        assert repository != null;
        ScriptFilter filter = toFilter(scriptFilter);
        return repository.getAllCount(user, false, filter);
    }

    public Uni<Script> getById(UUID id, IUser user) {
        assert repository != null;
        return repository.findById(id, user, false);
    }


}
