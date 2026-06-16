package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.rls.RLSRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class SoundFragmentRatingLogRepository extends AsyncRepository {

    // Append-only log of *confirmed* ratings. aivox only emits a rating once the
    // song has actually played for the user (tentative likes that get cancelled
    // before playback never reach jesoos), so every event here is a real decision.
    // The latest row per (user_id, sound_fragment_id) is the current state.
    private static final String SQL_INSERT =
            "INSERT INTO mixpla__sound_fragment_ratings_log (created_at, user_id, sound_fragment_id, brand_id, rating) " +
            "VALUES (now(), $1, $2, $3, $4)";

    public SoundFragmentRatingLogRepository() {
        super();
    }

    @Inject
    public SoundFragmentRatingLogRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    /** Appends a confirmed like (+1) or dislike (-1). */
    public Uni<Void> appendRating(long userId, UUID soundFragmentId, UUID brandId, int rating) {
        return client.preparedQuery(SQL_INSERT)
                .execute(Tuple.of(userId, soundFragmentId, brandId, rating))
                .replaceWithVoid();
    }
}
