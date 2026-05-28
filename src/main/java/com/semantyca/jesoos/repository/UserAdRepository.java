package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.mixpla.model.UserAd;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.USER_AD;

@ApplicationScoped
public class UserAdRepository extends AsyncRepository {

    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(USER_AD);

    @Inject
    public UserAdRepository(Pool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    public Uni<UUID> insert(UserAd entity, IUser user) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (author, reg_date, last_mod_user, last_mod_date, user_id, title, description, contacts) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id";
        OffsetDateTime now = OffsetDateTime.now();
        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addOffsetDateTime(now)
                .addLong(user.getId())
                .addOffsetDateTime(now)
                .addLong(entity.getUserId())
                .addString(entity.getTitle())
                .addString(entity.getDescription())
                .addString(entity.getContacts());
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"));
    }
}
