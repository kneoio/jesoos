package com.semantyca.djinn.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.SOUND_FRAGMENT;


@ApplicationScoped
public class SoundFragmentRepository extends SoundFragmentRepositoryAbstract {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SOUND_FRAGMENT);
    private final SoundFragmentQueryBuilder queryBuilder;

    public SoundFragmentRepository() {
        super();
        this.queryBuilder = null;
    }

    @Inject
    public SoundFragmentRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository,
                                   SoundFragmentQueryBuilder queryBuilder) {
        super(client, mapper, rlsRepository);
        this.queryBuilder = queryBuilder;
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset,
                                           final IUser user, final SoundFragmentFilter filter) {
        assert queryBuilder != null;
        String sql = queryBuilder.buildGetAllQuery(entityData.getTableName(), entityData.getRlsName(),
                user, false, filter, limit, offset);

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm()))
                    .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                    .onItem().transformToUni(row -> from(row, false, false, false))
                    .concatenate()
                    .collect().asList();
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, SoundFragmentFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId() + " AND t.archived = 0";

        if (filter != null && filter.isActivated()) {
            assert queryBuilder != null;
            sql += queryBuilder.buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm()))
                    .onItem().transform(rows -> rows.iterator().next().getInteger(0));
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<SoundFragment> findById(UUID uuid, Long userID, boolean includeGenres, boolean includeFiles) {
        String sql = "SELECT theTable.*, rls.*" +
                String.format(" FROM %s theTable JOIN %s rls ON theTable.id = rls.entity_id ", entityData.getTableName(), entityData.getRlsName()) +
                "WHERE rls.reader = $1 AND theTable.id = $2 AND theTable.archived = 0";

        return client.preparedQuery(sql)
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, includeGenres, includeFiles, true);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }

    public Uni<SoundFragment> findById(UUID uuid) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, false, false, false);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }

    public Uni<SoundFragment> findByArtistAndDate(String artist, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " " +
                "WHERE artist = $1 AND reg_date >= $2 AND reg_date < $3 AND archived = 0 " +
                "ORDER BY reg_date DESC LIMIT 1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(artist, startOfDay, endOfDay))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, false, false, false);
                    } else {
                        return Uni.createFrom().nullItem();
                    }
                });
    }

    public Uni<List<BrandSoundFragment>> getForBrandBySimilarity(UUID brandId, String keyword, final int limit, final int offset,
                                                                 boolean includeArchived, IUser user) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findForBrandBySimilarity(brandId, keyword, limit, offset, includeArchived, user);
    }


    public Uni<List<SoundFragment>> findByTypeAndBrand(PlaylistItemType type, UUID brandId, int limit, int offset) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.getBrandSongs(brandId, type, limit, offset);
    }

}