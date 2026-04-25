package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.rls.RLSRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PlaylistQueueRepository extends AsyncRepository {

    @Inject
    public PlaylistQueueRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<JsonArray> getQueueByBrandSlug(String brandSlug) {
        return client.preparedQuery("SELECT full_queue FROM mixpla__playlist_queue_state WHERE brand_slug = $1")
                .execute(Tuple.of(brandSlug))
                .onItem().transform(rows -> {
                    if (!rows.iterator().hasNext()) {
                        return new JsonArray();
                    }
                    JsonArray queue = rows.iterator().next().getJsonArray("full_queue");
                    return queue != null ? queue : new JsonArray();
                });
    }
}
