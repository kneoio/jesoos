package com.semantyca.djinn.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.mixpla.model.Profile;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
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

import java.util.List;
import java.util.UUID;

import static com.semantyca.djinn.repository.MixplaNameResolver.PROFILE;


@ApplicationScoped
public class ProfileRepository extends AsyncRepository {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(PROFILE);

    @Inject
    public ProfileRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Profile>> getAll(int limit, int offset, boolean includeArchived, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                " WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        sql += " ORDER BY t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                " WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Profile> findById(UUID id) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(id);
                });
    }

    public Uni<Profile> findByName(String name) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE name = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(name))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(name);
                });
    }


    public Uni<Integer> getAllCount(IUser user) {
        return getAllCount(user.getId(), entityData.getTableName(), entityData.getRlsName());
    }

    private Profile from(Row row) {
        Profile profile = new Profile();
        setDefaultFields(profile, row);

        profile.setName(row.getString("name"));
        profile.setDescription(row.getString("description"));
        profile.setExplicitContent(row.getBoolean("explicit_content"));
        profile.setArchived(row.getInteger("archived"));

        return profile;
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}