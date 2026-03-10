package com.semantyca.djinn.service;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.PromptDTO;
import com.semantyca.djinn.repository.prompt.PromptRepository;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.filter.PromptFilter;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PromptService extends AbstractService<Prompt, PromptDTO> {
    private final PromptRepository repository;

    @Inject
    public PromptService(UserService userService, PromptRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<Prompt>> getAll(final int limit, final int offset, final IUser user, final PromptFilter filter) {
        return repository.getAll(limit, offset, false, user, filter);
    }

    public Uni<Integer> getAllCount(final IUser user, final PromptFilter filter) {
        return repository.getAllCount(user, false, filter);
    }

    public Uni<Prompt> getById(UUID id, IUser user) {
        return repository.findById(id, user, false);
    }

   public Uni<Prompt> findByMasterAndLanguage(UUID masterId, LanguageTag languageCode, boolean includeArchived) {
        return repository.findByMasterAndLanguage(masterId, languageCode, includeArchived);
    }

}
