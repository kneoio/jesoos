package com.semantyca.djinn.service;

import com.semantyca.djinn.repository.brand.BrandRepository;
import com.semantyca.mixpla.model.brand.Brand;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class BrandService {
    private final BrandRepository repository;

    @Inject
    public BrandService(BrandRepository repository) {
        this.repository = repository;
    }

    public Uni<List<Brand>> getAll(final int limit, final int offset) {
        return repository.getAll(limit, offset, false, SuperUser.build(), null, null);
    }

    public Uni<List<Brand>> getAll(final int limit, final int offset, IUser user) {
        return repository.getAll(limit, offset, false, user, null, null);
    }

    public Uni<Brand> getById(UUID id, IUser user) {
        return repository.findById(id, user, true);
    }


    public Uni<Brand> getBySlugName(String name) {
        return repository.getBySlugName(name);
    }


}