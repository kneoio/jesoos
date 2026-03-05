package com.semantyca.djinn.service;

import com.semantyca.djinn.dto.ProfileDTO;
import com.semantyca.djinn.repository.ProfileRepository;
import com.semantyca.mixpla.model.Profile;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProfileService extends AbstractService<Profile, ProfileDTO> {

    private final ProfileRepository repository;


    @Inject
    public ProfileService(UserService userService, ProfileRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<ProfileDTO>> getAll(final int limit, final int offset, final IUser user) {
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<ProfileDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user, false);
    }

    @Override
    public Uni<ProfileDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id).chain(this::mapToDTO);
    }

    public Uni<Profile> getById(UUID id) {
        return repository.findById(id);
    }

    public Uni<Profile> findByName(String name) {
        return repository.findByName(name);
    }

    private Uni<ProfileDTO> mapToDTO(Profile profile) {
        return Uni.combine().all().unis(
                userService.getUserName(profile.getAuthor()),
                userService.getUserName(profile.getLastModifier())
        ).asTuple().map(tuple -> {
            ProfileDTO dto = new ProfileDTO();
            dto.setId(profile.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(profile.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(profile.getLastModifiedDate());
            dto.setName(profile.getName());
            dto.setDescription(profile.getDescription());
            dto.setExplicitContent(profile.isExplicitContent());
            return dto;
        });
    }



}