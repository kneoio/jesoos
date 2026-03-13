package com.semantyca.jesoos.repository.soundfragment;

import com.semantyca.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentBrandAssociationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentBrandAssociationHandler.class);

    public Uni<Void> updateBrandAssociations(SqlClient tx, UUID soundFragmentId, List<UUID> representedInBrands, IUser user) {
        if (representedInBrands == null) {
            return Uni.createFrom().voidItem();
        }

        String getCurrentBrandsSql = "SELECT brand_id FROM kneobroadcaster__brand_sound_fragments WHERE sound_fragment_id = $1";

        return tx.preparedQuery(getCurrentBrandsSql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToUni(currentRows -> {
                    List<UUID> currentBrands = new ArrayList<>();
                    currentRows.forEach(row -> currentBrands.add(row.getUUID("brand_id")));

                    List<UUID> brandsToAdd = representedInBrands.stream()
                            .filter(brand -> !currentBrands.contains(brand))
                            .toList();

                    List<UUID> brandsToRemove = currentBrands.stream()
                            .filter(brand -> !representedInBrands.contains(brand))
                            .toList();

                    Uni<Void> removeUni = removeBrands(tx, soundFragmentId, brandsToRemove);
                    Uni<Void> addUni = addBrands(tx, soundFragmentId, brandsToAdd);

                    return Uni.combine().all().unis(removeUni, addUni).discardItems();
                });
    }

    public Uni<Void> insertBrandAssociations(SqlClient tx, UUID soundFragmentId, List<UUID> representedInBrands, IUser user) {
        if (representedInBrands == null || representedInBrands.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return addBrands(tx, soundFragmentId, representedInBrands);
    }

    private Uni<Void> removeBrands(SqlClient tx, UUID soundFragmentId, List<UUID> brandsToRemove) {
        if (brandsToRemove.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String deleteBrandsSql = "DELETE FROM kneobroadcaster__brand_sound_fragments WHERE sound_fragment_id = $1 AND brand_id = ANY($2)";
        UUID[] brandsArray = brandsToRemove.toArray(new UUID[0]);
        return tx.preparedQuery(deleteBrandsSql)
                .execute(Tuple.of(soundFragmentId, brandsArray))
                .onItem().ignore().andContinueWithNull();
    }

    private Uni<Void> addBrands(SqlClient tx, UUID soundFragmentId, List<UUID> brandsToAdd) {
        if (brandsToAdd.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String insertBrandsSql = "INSERT INTO kneobroadcaster__brand_sound_fragments (brand_id, sound_fragment_id, played_by_brand_count, last_time_played_by_brand) VALUES ($1, $2, 0, NULL)";
        List<Tuple> insertParams = brandsToAdd.stream()
                .map(brandId -> Tuple.of(brandId, soundFragmentId))
                .collect(Collectors.toList());

        return tx.preparedQuery(insertBrandsSql)
                .executeBatch(insertParams)
                .onItem().ignore().andContinueWithNull();
    }
}
