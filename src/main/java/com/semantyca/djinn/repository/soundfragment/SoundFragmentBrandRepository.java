package com.semantyca.djinn.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.djinn.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import io.kneo.core.repository.rls.RLSRepository;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentBrandRepository extends SoundFragmentRepositoryAbstract {

    @Inject
    public SoundFragmentBrandRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<SoundFragment>> getBrandSongs(UUID brandId, PlaylistItemType fragmentType, final int limit, final int offset) {
        String sql = "SELECT t.*, " +
                "array_agg(DISTINCT sfg.genre_id) FILTER (WHERE sfg.genre_id IS NOT NULL) AS genre_ids, " +
                "array_agg(DISTINCT sfl.label_id) FILTER (WHERE sfl.label_id IS NOT NULL) AS label_ids " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "LEFT JOIN kneobroadcaster__sound_fragment_genres sfg ON sfg.sound_fragment_id = t.id " +
                "LEFT JOIN kneobroadcaster__sound_fragment_labels sfl ON sfl.id = t.id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0 AND t.type = $2 " +
                "GROUP BY t.id, bsf.played_by_brand_count " +
                "ORDER BY bsf.played_by_brand_count";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, fragmentType))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    private SoundFragment from(Row row) {
        SoundFragment doc = new SoundFragment();
        setDefaultFields(doc, row);
        doc.setSource(SourceType.valueOf(row.getString("source")));
        doc.setStatus(row.getInteger("status"));
        doc.setType(PlaylistItemType.valueOf(row.getString("type")));
        doc.setTitle(row.getString("title"));
        doc.setArtist(row.getString("artist"));
        doc.setAlbum(row.getString("album"));
        if (row.getValue("length") != null) {
            doc.setLength(Duration.ofMillis(row.getLong("length")));
        }
        doc.setArchived(row.getInteger("archived"));
        doc.setSlugName(row.getString("slug_name"));
        doc.setDescription(row.getString("description"));
        doc.setExpiresAt(row.getLocalDateTime("expires_at"));

        UUID[] genreArr = (UUID[]) row.getValue("genre_ids");
        doc.setGenres(genreArr != null ? List.of(genreArr) : List.of());

        UUID[] labelArr = (UUID[]) row.getValue("label_ids");
        doc.setLabels(labelArr != null ? List.of(labelArr) : List.of());

        doc.setFileMetadataList(List.of());
        return doc;
    }
}