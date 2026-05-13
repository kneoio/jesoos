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

    /**
     * Expands {@code full_queue} in the database and returns only each element's {@code dj} object,
     * preserving order and avoiding transfer of {@code tech} fields.
     */
    private static final String QUEUE_DJ_ONLY_SQL = """
            SELECT COALESCE(
                (
                    SELECT jsonb_agg(elem -> 'dj')
                    FROM jsonb_array_elements(
                        CASE
                            WHEN pqs.full_queue IS NOT NULL AND jsonb_typeof(pqs.full_queue) = 'array'
                            THEN pqs.full_queue
                            ELSE '[]'::jsonb
                        END
                    ) AS elem
                    WHERE jsonb_typeof(elem -> 'dj') = 'object'
                ),
                '[]'::jsonb
            ) AS dj_queue
            FROM mixpla__playlist_queue_state pqs
            WHERE pqs.brand_slug = $1
            """;

    @Inject
    public PlaylistQueueRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<Void> resetQueue(String brandSlug) {
        return client.preparedQuery(
                        "UPDATE mixpla__playlist_queue_state SET full_queue = '[]', updated_at = NOW() WHERE brand_slug = $1")
                .execute(Tuple.of(brandSlug))
                .replaceWithVoid();
    }

    public Uni<JsonArray> getQueueByBrandSlug(String brandSlug) {
        return client.preparedQuery(QUEUE_DJ_ONLY_SQL)
                .execute(Tuple.of(brandSlug))
                .onItem().transform(rows -> {
                    if (!rows.iterator().hasNext()) {
                        return new JsonArray();
                    }
                    JsonArray queue = rows.iterator().next().getJsonArray("dj_queue");
                    return queue != null ? queue : new JsonArray();
                });
    }
}
