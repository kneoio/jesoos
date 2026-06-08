package com.semantyca.jesoos.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.jesoos.model.stream.SharedSongEntry;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SharedSoundFragmentRepository extends SoundFragmentRepositoryAbstract {

    private static final String SSF_TABLE = "mixpla__shared_sound_fragments";

    @Inject
    public SharedSoundFragmentRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<SharedSongEntry>> findByBrand(UUID brandId, PlaylistItemType type, int limit, Set<UUID> excludeIds) {
        String sql = buildQuery(brandId, type, excludeIds, "sf.reg_date DESC", limit);
        return execute(sql);
    }

    public Uni<List<SharedSongEntry>> findByBrandRandom(UUID brandId, PlaylistItemType type, int limit, Set<UUID> excludeIds) {
        String sql = buildQuery(brandId, type, excludeIds, "RANDOM()", limit);
        return execute(sql);
    }

    private Uni<List<SharedSongEntry>> execute(String sql) {
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::fromRow)
                .concatenate()
                .collect().asList();
    }

    private Uni<SharedSongEntry> fromRow(Row row) {
        return from(row)
                .map(sf -> new SharedSongEntry(sf, row.getString("source_user_name")));
    }

    private String buildQuery(UUID brandId, PlaylistItemType type, Set<UUID> excludeIds, String orderBy, int limit) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT sf.*, ssf.source_user_name, ssf.source_user_email ")
                .append("FROM ").append(entityData.getTableName()).append(" sf ")
                .append("JOIN ").append(SSF_TABLE).append(" ssf ON ssf.sound_fragment_id = sf.id ")
                .append("WHERE ssf.target_brand_id = '").append(brandId).append("' ")
                .append("AND sf.archived = 0 ")
                .append("AND sf.status = 500 ");

        if (type != null) {
            sql.append("AND sf.type = '").append(type.name()).append("' ");
        }

        if (excludeIds != null && !excludeIds.isEmpty()) {
            String ids = excludeIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "));
            sql.append("AND sf.id NOT IN (").append(ids).append(") ");
        }

        sql.append("ORDER BY ").append(orderBy).append(" LIMIT ").append(limit);
        return sql.toString();
    }
}
