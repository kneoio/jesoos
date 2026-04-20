package com.semantyca.jesoos.service;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.radiostation.AiOverridingDTO;
import com.semantyca.jesoos.dto.radiostation.BrandDTO;
import com.semantyca.jesoos.dto.radiostation.BrandScriptEntryDTO;
import com.semantyca.jesoos.dto.radiostation.OwnerDTO;
import com.semantyca.jesoos.dto.radiostation.ProfileOverridingDTO;
import com.semantyca.jesoos.repository.brand.BrandRepository;
import com.semantyca.mixpla.model.brand.Brand;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandService extends AbstractService<Brand, BrandDTO> {
    private final BrandRepository repository;

    @Inject
    public BrandService(UserService userService, BrandRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<Integer> getAllCount(final IUser user, String country, final String query) {
        assert repository != null;
        return repository.getAllCount(user, false, country, query);
    }

    public Uni<Brand> getById(UUID id) {
        return repository.findById(id);
    }

    public Uni<Brand> getBySlugName(String name) {
        return repository.getBySlugName(name)
                .chain(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().nullItem();
                    }
                    return repository.getScriptEntriesForBrand(brand.getId())
                            .onItem().transform(scripts -> {
                                brand.setScripts(scripts);
                                return brand;
                            });
                });
    }
}