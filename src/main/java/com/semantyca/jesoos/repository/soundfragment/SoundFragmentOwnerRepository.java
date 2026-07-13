package com.semantyca.jesoos.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentOwnerRepository extends SoundFragmentRepositoryAbstract {
    private static final Logger LOGGER = Logger.getLogger(SoundFragmentOwnerRepository.class);

    @Inject
    public SoundFragmentOwnerRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    private String buildExcludeClause(Set<UUID> excludeIds) {
        if (excludeIds == null || excludeIds.isEmpty()) return "";
        String inList = excludeIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "));
        return "AND t.id NOT IN (" + inList + ") ";
    }

    public Uni<List<SoundFragment>> findByOwner(long userId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        return findOrdered(userId, filter, limit, excludeIds, "t.reg_date DESC");
    }

    public Uni<List<SoundFragment>> findByOwnerOldest(long userId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        return findOrdered(userId, filter, limit, excludeIds, "t.reg_date ASC");
    }

    public Uni<List<SoundFragment>> findByOwnerRandom(long userId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        return findOrdered(userId, filter, limit, excludeIds, "RANDOM()");
    }

    // Owner-scoped keyword search — mirrors SoundFragmentBrandRepository.findForBrandWithFilter's
    // keyword clause (search_name ILIKE + trigram similarity), but scoped to t.author instead of a brand.
    // Used by the OTS chat search path for owner-scoped one-time streams (no brand catalog behind them).
    public Uni<List<SoundFragment>> findByOwnerWithKeyword(long userId, String keyword, SoundFragmentFilter filter, int limit, int offset) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT t.* FROM ").append(entityData.getTableName()).append(" t ");
        sql.append("WHERE t.author = ").append(userId).append(" ");
        sql.append("AND t.archived = 0 ");
        if (hasKeyword) {
            sql.append("AND (t.search_name ILIKE '%' || $1 || '%' OR similarity(t.search_name, $1) > 0.05) ");
        }
        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }
        sql.append("ORDER BY t.reg_date DESC ");
        if (limit > 0) sql.append("LIMIT ").append(limit).append(" ");
        if (offset > 0) sql.append("OFFSET ").append(offset);

        LOGGER.debugf("findByOwnerWithKeyword (owner) SQL: %s", sql);

        io.vertx.mutiny.sqlclient.PreparedQuery<io.vertx.mutiny.sqlclient.RowSet<io.vertx.mutiny.sqlclient.Row>> query =
                client.preparedQuery(sql.toString());
        Uni<io.vertx.mutiny.sqlclient.RowSet<io.vertx.mutiny.sqlclient.Row>> exec = hasKeyword
                ? query.execute(io.vertx.mutiny.sqlclient.Tuple.of(keyword.toLowerCase()))
                : query.execute();
        return exec
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    private Uni<List<SoundFragment>> findOrdered(long userId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds, String orderBy) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT t.* FROM ").append(entityData.getTableName()).append(" t ");
        sql.append("WHERE t.author = ").append(userId).append(" ");
        sql.append("AND t.archived = 0 ");
        sql.append(buildExcludeClause(excludeIds));

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        sql.append(" ORDER BY ").append(orderBy).append(" ");

        if (limit > 0) {
            sql.append("LIMIT ").append(limit);
        }

        LOGGER.debugf("findOrdered (owner) SQL: %s", sql);

        return client.query(sql.toString())
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<List<SoundFragment>> findByIdsForOwner(long userId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        String placeholders = ids.stream()
                .map(id -> "'" + id.toString() + "'")
                .collect(Collectors.joining(","));
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t "
                + "WHERE t.id IN (" + placeholders + ") AND t.author = " + userId + " AND t.archived = 0 "
                + "ORDER BY t.reg_date DESC";
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }
}
