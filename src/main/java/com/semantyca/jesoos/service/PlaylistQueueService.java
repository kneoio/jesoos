package com.semantyca.jesoos.service;

import com.semantyca.jesoos.repository.PlaylistQueueRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PlaylistQueueService {

    @Inject
    PlaylistQueueRepository repository;

    public Uni<JsonArray> getQueueByBrandSlug(String brandSlug) {
        return repository.getQueueByBrandSlug(brandSlug);
    }
}
