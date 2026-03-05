package com.semantyca.djinn.service.soundfragment;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class BrandSoundFragmentUpdateService {

    @Inject
    PgPool client;

    public Uni<Void> updatePlayedCountAsync(UUID brandId, UUID soundFragmentId) {
        String sql = "INSERT INTO kneobroadcaster__brand_sound_fragments " +
                "(brand_id, sound_fragment_id, played_by_brand_count, last_time_played_by_brand) " +
                "VALUES ($1, $2, 1, NOW()) " +
                "ON CONFLICT (brand_id, sound_fragment_id) DO UPDATE SET " +
                "played_by_brand_count = kneobroadcaster__brand_sound_fragments.played_by_brand_count + 1, " +
                "last_time_played_by_brand = NOW()";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, soundFragmentId))
                .replaceWithVoid();
    }
}
