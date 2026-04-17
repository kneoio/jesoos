package com.semantyca.jesoos.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.exception.DocumentModificationAccessException;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragment;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragmentFlat;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.officeframe.dto.GenreDTO;
import com.semantyca.officeframe.dto.LabelDTO;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlResult;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentBrandRepository extends SoundFragmentRepositoryAbstract {
    private static final Logger LOGGER = Logger.getLogger(SoundFragmentBrandRepository.class);

    @Inject
    public SoundFragmentBrandRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<BrandSoundFragment>> findForBrandBySimilarity(UUID brandId, String keyword, final int limit, final int offset, IUser user) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.rated_by_brand_count, bsf.last_time_played_by_brand, " +
                "similarity(t.search_name, $3) AS sim " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bsf.brand_id = $1 AND rls.reader = $2 AND  t.archived = 0";

        sql += " AND (t.search_name ILIKE '%' || $3 || '%' OR similarity(t.search_name, $3) > 0.05)";
        sql += " ORDER BY sim DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId(), keyword))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Uni<SoundFragment> soundFragmentUni = from(row, true, false, true);
                    return soundFragmentUni.onItem().transform(soundFragment -> {
                        BrandSoundFragment brandSoundFragment = createBrandSoundFragment(row, brandId);
                        brandSoundFragment.setSoundFragment(soundFragment);
                        return brandSoundFragment;
                    });
                })
                .concatenate()
                .collect().asList();
    }

    public Uni<List<BrandSoundFragment>> findForBrandWithFilter(UUID brandId, String keyword, SoundFragmentFilter filter, int limit, int offset, IUser user) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();

        StringBuilder sql = new StringBuilder()
                .append("SELECT t.*, bsf.played_by_brand_count, bsf.rated_by_brand_count, bsf.last_time_played_by_brand");
        if (hasKeyword) {
            sql.append(", similarity(t.search_name, $3) AS sim");
        }
        sql.append(" FROM ").append(entityData.getTableName()).append(" t ")
                .append("JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id ")
                .append("JOIN ").append(entityData.getRlsName()).append(" rls ON t.id = rls.entity_id ")
                .append("WHERE bsf.brand_id = $1 AND rls.reader = $2 AND t.archived = 0");

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

        Tuple params = hasKeyword ? Tuple.of(brandId, user.getId(), keyword) : Tuple.of(brandId, user.getId());

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

    public Uni<List<SoundFragment>> getBrandSongs(UUID brandId, PlaylistItemType fragmentType, final int limit, final int offset) {
        String sql = "SELECT t.* " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
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

    private Uni<BrandSoundFragmentFlat> createBrandSoundFragmentFlat(Row row, UUID brandId) {
        UUID soundFragmentId = row.getUUID("id");

        Uni<List<LabelDTO>> labelsUni = loadLabels(soundFragmentId);  //directly DTO
        Uni<List<GenreDTO>> genresUni = loadGenres(soundFragmentId);  //directly DTO

        return Uni.combine().all().unis(labelsUni, genresUni).asTuple()
                .onItem().transform(tuple -> {
                    List<LabelDTO> labels = tuple.getItem1();
                    List<GenreDTO> genres = tuple.getItem2();

                    BrandSoundFragmentFlat flat = new BrandSoundFragmentFlat();
                    flat.setId(soundFragmentId);
                    flat.setDefaultBrandId(brandId);
                    flat.setPlayedByBrandCount(row.getInteger("played_by_brand_count"));
                    flat.setRatedByBrandCount(row.getInteger("rated_by_brand_count"));
                    flat.setPlayedTime(row.getLocalDateTime("last_time_played_by_brand"));
                    flat.setTitle(row.getString("title"));
                    flat.setArtist(row.getString("artist"));
                    flat.setAlbum(row.getString("album"));
                    flat.setSource(SourceType.valueOf(row.getString("source")));
                    flat.setLabels(labels);
                    flat.setGenres(genres);
                    return flat;
                });
    }

    private Uni<List<LabelDTO>> loadLabels(UUID soundFragmentId) {
        String sql = "SELECT l.id, l.identifier, l.color, l.font_color FROM __labels l " +
                "JOIN kneobroadcaster__sound_fragment_labels sfl ON l.id = sfl.label_id " +
                "WHERE sfl.id = $1 ORDER BY l.identifier";
        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    LabelDTO dto = new LabelDTO();
                    dto.setId(row.getUUID("id"));
                    dto.setIdentifier(row.getString("identifier"));
                    dto.setColor(row.getString("color"));
                    dto.setFontColor(row.getString("font_color"));
                    return dto;
                })
                .collect().asList();
    }

    private Uni<List<GenreDTO>> loadGenres(UUID soundFragmentId) {
        String sql = "SELECT g.id, g.identifier, g.color, g.font_color, g.rank FROM __genres g " +
                "JOIN kneobroadcaster__sound_fragment_genres sfg ON g.id = sfg.genre_id " +
                "WHERE sfg.sound_fragment_id = $1 ORDER BY g.identifier";
        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    GenreDTO dto = new GenreDTO();
                    dto.setId(row.getUUID("id"));
                    dto.setIdentifier(row.getString("identifier"));
                    dto.setColor(row.getString("color"));
                    dto.setFontColor(row.getString("font_color"));
                    dto.setRank(row.getInteger("rank"));
                    return dto;
                })
                .collect().asList();
    }

    private BrandSoundFragment createBrandSoundFragment(Row row, UUID brandId) {
        BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
        brandSoundFragment.setId(row.getUUID("id"));
        brandSoundFragment.setDefaultBrandId(brandId);
        brandSoundFragment.setPlayedByBrandCount(row.getInteger("played_by_brand_count"));
        brandSoundFragment.setRatedByBrandCount(row.getInteger("rated_by_brand_count"));
        brandSoundFragment.setPlayedTime(row.getLocalDateTime("last_time_played_by_brand"));
        return brandSoundFragment;
    }

    public Uni<Integer> updateRatedByBrandCount(UUID brandId, UUID soundFragmentId, int delta, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), soundFragmentId)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException(
                                "User does not have edit permission", user.getUserName(), soundFragmentId
                        ));
                    }

                    String selectSql = "SELECT rated_by_brand_count, last_rated_at FROM kneobroadcaster__brand_sound_fragments " +
                            "WHERE brand_id = $1 AND sound_fragment_id = $2";

                    return client.preparedQuery(selectSql)
                            .execute(Tuple.of(brandId, soundFragmentId))
                            .onItem().transformToUni(rowSet -> {
                                int currentRating = 100;
                                java.time.LocalDateTime lastRatedAt = null;

                                if (rowSet.iterator().hasNext()) {
                                    Row row = rowSet.iterator().next();
                                    Integer dbValue = row.getInteger("rated_by_brand_count");
                                    if (dbValue != null) {
                                        currentRating = dbValue;
                                    }
                                    lastRatedAt = row.getLocalDateTime("last_rated_at");
                                }

                                if (lastRatedAt != null) {
                                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                                    long secondsSinceLastRating = java.time.Duration.between(lastRatedAt, now).getSeconds();

                                    if (secondsSinceLastRating < 2) {
                                        boolean sameDirection = (delta > 0 && currentRating > 100) || (delta < 0 && currentRating < 100);
                                        if (sameDirection) {
                                            return Uni.createFrom().failure(new IllegalStateException(
                                                    "Please wait before rating again."
                                            ));
                                        }
                                    }
                                }

                                int newRating = currentRating + delta;
                                if (newRating < 0) {
                                    newRating = 0;
                                } else if (newRating > 200) {
                                    newRating = 200;
                                }

                                String updateSql = "UPDATE kneobroadcaster__brand_sound_fragments " +
                                        "SET rated_by_brand_count = $1, last_rated_at = NOW() " +
                                        "WHERE brand_id = $2 AND sound_fragment_id = $3";

                                Tuple updateParams = Tuple.of(newRating, brandId, soundFragmentId);

                                return client.preparedQuery(updateSql)
                                        .execute(updateParams)
                                        .onItem().transform(SqlResult::rowCount);
                            });
                });
    }

    public Uni<List<SoundFragment>> findByFilter(UUID brandId, SoundFragmentFilter filter, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT t.* FROM ").append(entityData.getTableName()).append(" t ");
        sql.append("JOIN kneobroadcaster__brand_sound_fragments bsf ON bsf.sound_fragment_id = t.id ");
        sql.append("WHERE bsf.brand_id = '").append(brandId).append("' ");
        sql.append("AND t.archived = 0 ");

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        sql.append(" ORDER BY RANDOM() ");

        if (limit > 0) {
            sql.append("LIMIT ").append(limit);
        }

        LOGGER.debugf("findByFilter SQL: %s", sql);

        return client.query(sql.toString())
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }
}
