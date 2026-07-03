package com.semantyca.jesoos.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentBrandRepository extends SoundFragmentRepositoryAbstract {
    private static final Logger LOGGER = Logger.getLogger(SoundFragmentBrandRepository.class);

    @Inject
    public SoundFragmentBrandRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }


    public Uni<List<BrandSoundFragment>> findForBrandWithFilter(UUID brandId, String keyword, SoundFragmentFilter filter, int limit, int offset, IUser user) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();

        StringBuilder sql = new StringBuilder()
                .append("SELECT t.*, bsf.played_by_brand_count, bsf.last_time_played_by_brand");
        if (hasKeyword) {
            sql.append(", similarity(t.search_name, $3) AS sim");
        }
        sql.append(" FROM ").append(entityData.getTableName()).append(" t ")
                .append("JOIN mixpla__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id ")
                .append("JOIN ").append(entityData.getRlsName()).append(" rls ON t.id = rls.entity_id ")
                .append("WHERE bsf.brand_id = $1 AND rls.reader = $2 AND t.archived = 0 AND t.type = 'SONG'");

        if (hasKeyword) {
            sql.append(" AND (t.search_name ILIKE '%' || $3 || '%' OR similarity(t.search_name, $3) > 0.05)");
        }
        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }
        sql.append(hasKeyword ? " ORDER BY sim DESC" : " ORDER BY bsf.last_time_played_by_brand DESC NULLS LAST");
        if (limit > 0) {
            sql.append(String.format(" LIMIT %s OFFSET %s", limit, offset));
        }

        Tuple params = hasKeyword ? Tuple.of(brandId, user.getId(), keyword.toLowerCase()) : Tuple.of(brandId, user.getId());

        return client.preparedQuery(sql.toString())
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, true, false, true).onItem().transform(soundFragment -> {
                    BrandSoundFragment bsf = createBrandSoundFragment(row, brandId);
                    bsf.setSoundFragment(soundFragment);
                    return bsf;
                }))
                .concatenate()
                .collect().asList();
    }

    public Uni<io.vertx.core.json.JsonObject> getBrandCatalogSummary(UUID brandId) {
        String artistsSql = "SELECT t.artist, COUNT(*) AS song_count " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN mixpla__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0 AND t.type = 'SONG' AND t.artist IS NOT NULL AND t.artist <> '' " +
                "GROUP BY t.artist ORDER BY song_count DESC";

        String genresSql = "SELECT g.identifier, COUNT(DISTINCT t.id) AS song_count " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN mixpla__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN mixpla__sound_fragment_genres sfg ON sfg.sound_fragment_id = t.id " +
                "JOIN __genres g ON g.id = sfg.genre_id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0 AND t.type = 'SONG' " +
                "GROUP BY g.identifier ORDER BY song_count DESC";

        String labelsSql = "SELECT l.identifier, COUNT(DISTINCT t.id) AS song_count " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN mixpla__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN mixpla__sound_fragment_labels sfl ON sfl.id = t.id " +
                "JOIN __labels l ON l.id = sfl.label_id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0 AND t.type = 'SONG' " +
                "GROUP BY l.identifier ORDER BY song_count DESC";

        String totalSql = "SELECT COUNT(*) AS total FROM " + entityData.getTableName() + " t " +
                "JOIN mixpla__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0 AND t.type = 'SONG'";

        Uni<RowSet<Row>> artistsUni = client.preparedQuery(artistsSql).execute(Tuple.of(brandId));
        Uni<RowSet<Row>> genresUni = client.preparedQuery(genresSql).execute(Tuple.of(brandId));
        Uni<RowSet<Row>> labelsUni = client.preparedQuery(labelsSql).execute(Tuple.of(brandId));
        Uni<RowSet<Row>> totalUni = client.preparedQuery(totalSql).execute(Tuple.of(brandId));

        return Uni.combine().all().unis(artistsUni, genresUni, labelsUni, totalUni).asTuple()
                .map(tuple -> {
                    JsonArray artists = new JsonArray();
                    tuple.getItem1().forEach(row -> artists.add(
                            new JsonObject()
                                    .put("artist", row.getString("artist"))
                                    .put("songCount", row.getLong("song_count"))
                    ));
                    JsonArray genres = new JsonArray();
                    tuple.getItem2().forEach(row -> genres.add(
                            new JsonObject()
                                    .put("genre", row.getString("identifier"))
                                    .put("songCount", row.getLong("song_count"))
                    ));
                    JsonArray labels = new JsonArray();
                    tuple.getItem3().forEach(row -> labels.add(
                            new JsonObject()
                                    .put("label", row.getString("identifier"))
                                    .put("songCount", row.getLong("song_count"))
                    ));
                    long total = tuple.getItem4().iterator().next().getLong("total");
                    return new JsonObject()
                            .put("totalTracks", total)
                            .put("artists", artists)
                            .put("genres", genres)
                            .put("labels", labels);
                });
    }

    public Uni<List<SoundFragment>> getBrandSongs(UUID brandId, PlaylistItemType fragmentType, final int limit, final int offset) {
        String sql = "SELECT t.* " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN mixpla__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0 AND t.type = $2 " +
                "ORDER BY bsf.played_by_brand_count";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        Tuple params = Tuple.of(brandId, fragmentType);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, true, true, true))
                .concatenate()
                .collect().asList();
    }

    private BrandSoundFragment createBrandSoundFragment(Row row, UUID brandId) {
        BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
        brandSoundFragment.setId(row.getUUID("id"));
        brandSoundFragment.setDefaultBrandId(brandId);
        brandSoundFragment.setPlayedByBrandCount(row.getInteger("played_by_brand_count"));
        java.time.LocalDateTime playedTimeRaw = row.getLocalDateTime("last_time_played_by_brand");
        brandSoundFragment.setPlayedTime(playedTimeRaw != null ? playedTimeRaw.atOffset(ZoneOffset.UTC) : null);
        return brandSoundFragment;
    }

    private String buildExcludeClause(Set<UUID> excludeIds) {
        if (excludeIds == null || excludeIds.isEmpty()) return "";
        String inList = excludeIds.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(", "));
        return "AND t.id NOT IN (" + inList + ") ";
    }

    public Uni<List<SoundFragment>> findByFilter(UUID brandId, SoundFragmentFilter filter, int limit) {
        return findByFilter(brandId, filter, limit, Set.of());
    }

    public Uni<List<SoundFragment>> findByFilter(UUID brandId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT t.* FROM ").append(entityData.getTableName()).append(" t ");
        sql.append("JOIN mixpla__brand_sound_fragments bsf ON bsf.sound_fragment_id = t.id ");
        sql.append("WHERE bsf.brand_id = '").append(brandId).append("' ");
        sql.append("AND t.archived = 0 ");
        sql.append("AND (t.source != 'CONTRIBUTION' OR t.status = 12) ");
        sql.append(buildExcludeClause(excludeIds));

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        sql.append("AND COALESCE(bsf.boost, 0) > -1 ");
        sql.append(" ORDER BY COALESCE(bsf.boost, 0) DESC, ")
                .append("COALESCE(bsf.played_by_brand_count, 0) ASC, ")
                .append("t.id ASC ");

        if (limit > 0) {
            sql.append("LIMIT ").append(limit);
        }

        LOGGER.debugf("findByFilter SQL: %s", sql);

        return client.query(sql.toString())
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<List<SoundFragment>> findByFilterOldest(UUID brandId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT t.* FROM ").append(entityData.getTableName()).append(" t ");
        sql.append("JOIN mixpla__brand_sound_fragments bsf ON bsf.sound_fragment_id = t.id ");
        sql.append("WHERE bsf.brand_id = '").append(brandId).append("' ");
        sql.append("AND t.archived = 0 ");
        sql.append("AND (t.source != 'CONTRIBUTION' OR t.status = 12) ");
        sql.append(buildExcludeClause(excludeIds));

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        sql.append("AND COALESCE(bsf.boost, 0) > -1 ");
        sql.append(" ORDER BY COALESCE(bsf.boost, 0) DESC, ")
                .append("COALESCE(bsf.played_by_brand_count, 0) DESC, ")
                .append("t.id ASC ");

        if (limit > 0) {
            sql.append("LIMIT ").append(limit);
        }

        LOGGER.debugf("findByFilterOldest SQL: %s", sql);

        return client.query(sql.toString())
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<List<SoundFragment>> findByFilterRandom(UUID brandId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT t.* FROM ").append(entityData.getTableName()).append(" t ");
        sql.append("JOIN mixpla__brand_sound_fragments bsf ON bsf.sound_fragment_id = t.id ");
        sql.append("WHERE bsf.brand_id = '").append(brandId).append("' ");
        sql.append("AND t.archived = 0 ");
        sql.append("AND (t.source != 'CONTRIBUTION' OR t.status = 12) ");
        sql.append(buildExcludeClause(excludeIds));

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        sql.append(" ORDER BY RANDOM() * CASE COALESCE(bsf.boost, 0) ")
                .append("WHEN 2 THEN 4.0 WHEN 1 THEN 2.0 WHEN -1 THEN 0.05 ELSE 1.0 END DESC ");

        if (limit > 0) {
            sql.append("LIMIT ").append(limit);
        }

        LOGGER.debugf("findByFilterRandom SQL: %s", sql);

        return client.query(sql.toString())
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<List<SoundFragment>> findActiveScheduledByBrand(UUID brandId) {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t " +
                "JOIN mixpla__brand_sound_fragments bsf ON bsf.sound_fragment_id = t.id " +
                "WHERE bsf.brand_id = $1 " +
                "AND t.archived = 0 " +
                "AND t.scheduler IS NOT NULL " +
                "AND t.type IN ('PRERECORDED_ADVERTISEMENT', 'PRERECORDED_PODCAST')";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .select().where(sf -> sf.getScheduler() != null && sf.getScheduler().isEnabled())
                .collect().asList();
    }
}
