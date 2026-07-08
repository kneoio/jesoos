package com.semantyca.jesoos.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.rls.RlsActionUtil;
import com.semantyca.jesoos.model.stream.SharedSongEntry;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SharedSoundFragment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SharedSoundFragmentRepository extends SoundFragmentRepositoryAbstract {

    private static final String SSF_TABLE = "mixpla__shared_sound_fragments";
    private static final String SSF_RLS_TABLE = "mixpla__shared_sound_fragment_readers";
    private static final String BRANDS_TABLE = "mixpla__brands";

    // Mirrors datanest's ApprovalStatus.PENDING — datanest owns that enum, jesoos only ever
    // writes this one value (a fresh contribution share always starts PENDING). See
    // datanest's repository/soundfragment/SHARING_WORKFLOW.md.
    private static final int PENDING_STATUS = 506;

    @Inject
    public SharedSoundFragmentRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    // Creates a PENDING share from a chat contribution's submitter to the target station — the
    // only way a chat-uploaded song becomes visible to a station owner (see SHARING_WORKFLOW.md
    // in datanest, which this mirrors). The underlying SoundFragment gets no brand association
    // and no station RLS at creation; visibility comes entirely from this share row until the
    // station owner accepts it (accept/reject handled in datanest/mixdeck, not here).
    public Uni<Void> shareContribution(UUID soundFragmentId, UUID targetBrandId, long sourceUserId,
                                        String sourceUserName, String sourceUserEmail) {
        return client.withTransaction(tx -> insertShareInTx(tx, soundFragmentId, targetBrandId,
                sourceUserId, sourceUserName, sourceUserEmail));
    }

    private Uni<Void> insertShareInTx(SqlClient tx, UUID soundFragmentId, UUID targetBrandId,
                                       long sourceUserId, String sourceUserName, String sourceUserEmail) {
        String upsertSql = "INSERT INTO " + SSF_TABLE + " " +
                "(source_user_id, target_brand_id, sound_fragment_id, expires_at, played_count, rated_count, status, archived, source_user_name, source_user_email, author, last_mod_user, reg_date, last_mod_date) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, NOW(), NOW()) " +
                "ON CONFLICT ON CONSTRAINT unique_brand_shared_fragment " +
                "DO UPDATE SET archived = 0, status = EXCLUDED.status, source_user_id = EXCLUDED.source_user_id, " +
                "source_user_name = EXCLUDED.source_user_name, source_user_email = EXCLUDED.source_user_email, " +
                "last_mod_user = EXCLUDED.last_mod_user, last_mod_date = NOW() " +
                "RETURNING id";
        Tuple params = Tuple.tuple()
                .addValue(sourceUserId)
                .addUUID(targetBrandId)
                .addUUID(soundFragmentId)
                .addOffsetDateTime(null)
                .addInteger(0)
                .addInteger(100)
                .addInteger(PENDING_STATUS)
                .addInteger(0)
                .addValue(sourceUserName)
                .addValue(sourceUserEmail)
                .addValue(sourceUserId)
                .addValue(sourceUserId);
        return tx.preparedQuery(upsertSql)
                .execute(params)
                .onItem().transformToUni(rows -> {
                    if (!rows.iterator().hasNext()) {
                        return Uni.createFrom().voidItem();
                    }
                    UUID id = rows.iterator().next().getUUID("id");
                    return insertRlsForReceivers(tx, id, targetBrandId);
                });
    }

    // Grants share-entity RLS (on the SharedSoundFragment row, not the underlying fragment) to the
    // target brand's owner AND co-owners, so all of them see the offer in their "received" inbox.
    // Mirrors datanest's SharedSoundFragmentRepository.insertRlsForReceivers.
    private Uni<Void> insertRlsForReceivers(SqlClient tx, UUID entityId, UUID targetBrandId) {
        String ownerSql = "INSERT INTO " + SSF_RLS_TABLE + " (reader, entity_id, can_edit, can_delete) " +
                "SELECT (b.owner->>'userId')::bigint, $1, false, false " +
                "FROM " + BRANDS_TABLE + " b WHERE b.id = $2 AND b.owner->>'userId' IS NOT NULL " +
                "ON CONFLICT (reader, entity_id) DO UPDATE SET reading_time = now()";
        String coOwnersSql = "INSERT INTO " + SSF_RLS_TABLE + " (reader, entity_id, can_edit, can_delete) " +
                "SELECT (co->>'userId')::bigint, $1, false, false " +
                "FROM " + BRANDS_TABLE + " b, jsonb_array_elements(COALESCE(b.owner->'coOwners', '[]'::jsonb)) co " +
                "WHERE b.id = $2 AND co->>'userId' IS NOT NULL " +
                "ON CONFLICT (reader, entity_id) DO UPDATE SET reading_time = now()";
        return tx.preparedQuery(ownerSql).execute(Tuple.of(entityId, targetBrandId))
                .chain(() -> tx.preparedQuery(coOwnersSql).execute(Tuple.of(entityId, targetBrandId)))
                .chain(() -> RlsActionUtil.ensureSuperUserAccess(tx, SSF_RLS_TABLE, entityId));
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
                .map(sf -> {
                    Integer sharedBoost = row.getInteger("shared_boost");
                    if (sharedBoost != null) {
                        sf.setBoost(sharedBoost);
                    }
                    return new SharedSongEntry(sf, row.getString("source_user_name"));
                });
    }

    private String buildQuery(UUID brandId, PlaylistItemType type, Set<UUID> excludeIds, String orderBy, int limit) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT sf.*, ssf.source_user_name, ssf.source_user_email, ssf.boost AS shared_boost ")
                .append("FROM ").append(entityData.getTableName()).append(" sf ")
                .append("JOIN ").append(SSF_TABLE).append(" ssf ON ssf.sound_fragment_id = sf.id ")
                .append("WHERE ssf.target_brand_id = '").append(brandId).append("' ")
                .append("AND sf.archived = 0 ")
                .append("AND ssf.status = 505 ")
                .append("AND COALESCE(ssf.boost, 0) > -1 ");

        if (type != null) {
            sql.append("AND sf.type = '").append(type.name()).append("' ");
        }

        if (excludeIds != null && !excludeIds.isEmpty()) {
            String ids = excludeIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "));
            sql.append("AND sf.id NOT IN (").append(ids).append(") ");
        }

        if ("RANDOM()".equals(orderBy)) {
            sql.append("ORDER BY RANDOM() * CASE COALESCE(ssf.boost, 0) ")
                    .append("WHEN 2 THEN 4.0 WHEN 1 THEN 2.0 WHEN -1 THEN 0.05 ELSE 1.0 END DESC ");
        } else {
            sql.append("ORDER BY COALESCE(ssf.boost, 0) DESC, ").append(orderBy).append(" ");
        }
        sql.append("LIMIT ").append(limit);
        return sql.toString();
    }
}
