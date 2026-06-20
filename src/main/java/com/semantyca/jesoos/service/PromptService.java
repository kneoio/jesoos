package com.semantyca.jesoos.service;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.PromptDTO;
import com.semantyca.jesoos.repository.prompt.PromptRepository;
import com.semantyca.mixpla.model.DjPrompt;
import com.semantyca.mixpla.model.filter.PromptFilter;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PromptService extends AbstractService<DjPrompt, PromptDTO> {
    private final PromptRepository repository;

    @Inject
    public PromptService(UserService userService, PromptRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<DjPrompt>> getAll(final int limit, final int offset, final IUser user, final PromptFilter filter) {
        return repository.getAll(limit, offset, user, filter);
    }

    public Uni<Integer> getAllCount(final IUser user, final PromptFilter filter) {
        return repository.getAllCount(user, filter);
    }

    public Uni<DjPrompt> getById(UUID id, IUser user) {
        return repository.findById(id, user);
    }

    public Uni<DjPrompt> findByLanguage(UUID masterId, LanguageTag languageCode) {
        return repository.findByMasterAndLanguage(masterId, languageCode);
    }

    public record ResolvedPrompt(DjPrompt prompt, boolean fallBacked) {}

    public Uni<ResolvedPrompt> resolveForLanguage(UUID masterId, LanguageTag language) {
        return getById(masterId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == language) {
                        return Uni.createFrom().item(new ResolvedPrompt(masterPrompt, false));
                    }
                    return findByLanguage(masterId, language)
                            .map(p -> p != null
                                    ? new ResolvedPrompt(p, false)
                                    : new ResolvedPrompt(masterPrompt, true));
                });
    }

}
