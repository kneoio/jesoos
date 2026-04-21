package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.UserData;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.embedded.DocumentAccessInfo;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.exception.DocumentHasNotFoundException;
import com.semantyca.core.repository.exception.DocumentModificationAccessException;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.mixpla.model.BrandListener;
import com.semantyca.mixpla.model.Listener;
import com.semantyca.mixpla.model.filter.ListenerFilter;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.semantyca.mixpla.repository.MixplaNameResolver.LISTENER;


@ApplicationScoped
public class ListenersRepository extends AsyncRepository {

    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(LISTENER);

    @Inject
    public ListenersRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Listener>> getAll(int limit, int offset, boolean includeArchived, IUser user, ListenerFilter filter) {
        String sql = "SELECT t.*, rls.* FROM " + entityData.getTableName() + " t " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        sql += " ORDER BY t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Listener listener = from(row);
                    return loadLabels(listener.getId())
                            .onItem().transform(labels -> {
                                listener.setLabels(labels);
                                return listener;
                            });
                })
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, ListenerFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Listener> findById(UUID uuid, IUser user, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2";

        if (!includeArchived) {
            sql += " AND (theTable.archived IS NULL OR theTable.archived = 0)";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()))
                                .chain(listener -> loadLabels(listener.getId())
                                        .onItem().transform(labels -> {
                                            listener.setLabels(labels);
                                            return listener;
                                        }));
                    } else {
                        LOGGER.warn("No {} found with id: {}, user: {} ", LISTENER, uuid, user.getId());
                        throw new DocumentHasNotFoundException(uuid);
                    }
                });
    }

    public Uni<List<BrandListener>> findForBrand(String slugName, final int limit, final int offset, IUser user, boolean includeArchived, ListenerFilter filter) {
        String sql = "SELECT l.*, lb.brand_id, lb.rank " +
                "FROM " + entityData.getTableName() + " l " +
                "JOIN kneobroadcaster__listener_brands lb ON l.id = lb.listener_id " +
                "JOIN kneobroadcaster__brands b ON b.id = lb.brand_id " +
                "JOIN " + entityData.getRlsName() + " rls ON l.id = rls.entity_id " +
                "WHERE b.slug_name = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND l.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter, "l");
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().isEmpty()) {
            sql += " ORDER BY similarity(l.search_keywords, lower('" + filter.getSearchTerm().replace("'", "''") + "')) DESC, l.last_mod_date DESC";
        } else {
            sql += " ORDER BY l.last_mod_date DESC";
        }

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(slugName, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Listener listener = from(row);
                    return loadLabels(listener.getId())
                            .onItem().transform(labels -> {
                                listener.setLabels(labels);
                                BrandListener brandListener = new BrandListener();
                                brandListener.setId(row.getUUID("id"));
                                brandListener.setBrandId(row.getUUID("brand_id"));
                                brandListener.setRank(row.getInteger("rank"));
                                brandListener.setListener(listener);
                                return brandListener;
                            });
                })
                .concatenate()
                .collect().asList();
    }

    public Uni<List<UUID>> getBrandsForListener(UUID listenerId) {
        String sql = "SELECT lb.brand_id " +
                "FROM kneobroadcaster__listener_brands lb " +
                "WHERE lb.listener_id = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(listenerId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("brand_id"))
                .collect().asList();
    }


    public Uni<Void> addBrandToListener(UUID listenerId, UUID brandId) {
        LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();
        String sql = "INSERT INTO kneobroadcaster__listener_brands (listener_id, brand_id, reg_date, rank) " +
                "VALUES ($1, $2, $3, $4) " +
                "ON CONFLICT (listener_id, brand_id) DO NOTHING";

        return client.preparedQuery(sql)
                .execute(Tuple.of(listenerId, brandId, nowTime, 99))
                .replaceWithVoid();
    }

    public Uni<Listener> insert(Listener listener, List<UUID> representedInBrands, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

        String sql = "INSERT INTO " + entityData.getTableName() +
                " (user_id, author, reg_date, last_mod_user, last_mod_date, loc_name, nickname, user_data, archived) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id";

        JsonObject localizedNameJson = JsonObject.mapFrom(listener.getLocalizedName());
        JsonObject localizedNickNameJson = toNickNameJson(listener.getNickName());
        JsonObject userDataJson = toUserDataJson(listener.getUserData());

        Tuple params = Tuple.tuple()
                .addLong(listener.getUserId())
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addJsonObject(localizedNameJson)
                .addJsonObject(localizedNickNameJson)
                .addJsonObject(userDataJson)
                .addInteger(0);

        return client.withTransaction(tx ->
                tx.preparedQuery(sql)
                        .execute(params)
                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                        .onItem().transformToUni(id ->
                                insertRLSPermissions(tx, id, entityData, user)
                                        .onItem().transformToUni(ignored -> insertBrandAssociations(tx, id, representedInBrands, nowTime))
                                        .onItem().transformToUni(ignored -> upsertLabels(tx, id, listener.getLabels()))
                                        .onItem().transform(ignored -> id)
                        )
        ).onItem().transformToUni(id -> findById(id, user, true));
    }

    private Uni<Void> insertBrandAssociations(SqlClient tx, UUID listenerId, List<UUID> representedInBrands, LocalDateTime nowTime) {
        if (representedInBrands == null || representedInBrands.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String insertBrandsSql = "INSERT INTO kneobroadcaster__listener_brands (listener_id, brand_id, reg_date, rank) VALUES ($1, $2, $3, $4)";
        List<Tuple> insertParams = representedInBrands.stream()
                .map(brandId -> Tuple.of(listenerId, brandId, nowTime, 99))
                .collect(Collectors.toList());

        return tx.preparedQuery(insertBrandsSql)
                .executeBatch(insertParams)
                .onItem().ignore().andContinueWithNull();
    }

    public Uni<Listener> update(UUID id, Listener doc, List<UUID> representedInBrands, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onFailure().invoke(throwable -> LOGGER.error("Failed to check RLS permissions for update listener: {} by user: {}", id, user.getId(), throwable))
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException(
                                        "User does not have edit permission", user.getUserName(), id));
                            }

                            LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();
                            JsonObject localizedNameJson = JsonObject.mapFrom(doc.getLocalizedName());
                            JsonObject localizedNickNameJson = toNickNameJson(doc.getNickName());
                            JsonObject userDataJson = toUserDataJson(doc.getUserData());

                            return client.withTransaction(tx -> {
                                String sql = "UPDATE " + entityData.getTableName() +
                                        " SET loc_name=$1, nickname=$2, user_data=$3, last_mod_user=$4, last_mod_date=$5 " +
                                        "WHERE id=$6";

                                Tuple params = Tuple.tuple()
                                        .addJsonObject(localizedNameJson)
                                        .addJsonObject(localizedNickNameJson)
                                        .addJsonObject(userDataJson)
                                        .addLong(user.getId())
                                        .addLocalDateTime(nowTime)
                                        .addUUID(id);

                                return tx.preparedQuery(sql)
                                        .execute(params)
                                        .onFailure().invoke(throwable -> LOGGER.error("Failed to update listener: {} by user: {}", id, user.getId(), throwable))
                                        .onItem().transformToUni(rowSet -> {
                                            if (rowSet.rowCount() == 0) {
                                                return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                            }
                                            return updateBrandAssociations(tx, id, representedInBrands, nowTime)
                                                    .chain(() -> upsertLabels(tx, id, doc.getLabels()));
                                        });
                            }).onItem().transformToUni(ignored -> findById(id, user, true));
                        });
            } catch (Exception e) {
                LOGGER.error("Failed to prepare update parameters for listener: {} by user: {}", id, user.getId(), e);
                return Uni.createFrom().failure(e);
            }
        });
    }

    private Uni<Void> updateBrandAssociations(SqlClient tx, UUID listenerId, List<UUID> representedInBrands, LocalDateTime nowTime) {
        if (representedInBrands == null) {
            return Uni.createFrom().voidItem();
        }

        String deleteSql = "DELETE FROM kneobroadcaster__listener_brands WHERE listener_id = $1";
        String insertSql = "INSERT INTO kneobroadcaster__listener_brands (listener_id, brand_id, reg_date, rank) VALUES ($1, $2, $3, $4)";

        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(listenerId))
                .onItem().transformToUni(ignored -> {
                    if (representedInBrands.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    List<Tuple> insertParams = representedInBrands.stream()
                            .map(brandId -> Tuple.of(listenerId, brandId, nowTime, 99))
                            .collect(Collectors.toList());
                    return tx.preparedQuery(insertSql)
                            .executeBatch(insertParams)
                            .onItem().ignore().andContinueWithNull();
                });
    }


    private Listener from(Row row) {
        Listener doc = new Listener();
        setDefaultFields(doc, row);
        doc.setUserId(row.getLong("user_id"));

        JsonObject localizedNameJson = row.getJsonObject(COLUMN_LOCALIZED_NAME);
        if (localizedNameJson != null) {
            EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
            localizedNameJson.getMap().forEach((key, value) -> 
                localizedName.put(LanguageCode.valueOf(key), (String) value));
            doc.setLocalizedName(localizedName);
        }

        JsonObject nickName = row.getJsonObject("nickname");
        doc.setNickName(fromNickNameJson(nickName));

        JsonObject userDataJson = row.getJsonObject("user_data");
        doc.setUserData(fromUserDataJson(userDataJson));

        doc.setArchived(row.getInteger("archived"));
        return doc;
    }

    private JsonObject toNickNameJson(EnumMap<LanguageCode, Set<String>> nick) {
        JsonObject json = new JsonObject();
        if (nick == null || nick.isEmpty()) {
            return json;
        }
        nick.forEach((lang, set) -> {
            if (lang == null || set == null || set.isEmpty()) {
                return;
            }
            JsonArray arr = new JsonArray();
            for (String s : set) {
                if (s == null) {
                    continue;
                }
                String v = s.trim();
                if (!v.isEmpty()) {
                    arr.add(v);
                }
            }
            if (!arr.isEmpty()) {
                json.put(lang.name(), arr);
            }
        });
        return json;
    }

    private EnumMap<LanguageCode, Set<String>> fromNickNameJson(JsonObject json) {
        EnumMap<LanguageCode, Set<String>> out = new EnumMap<>(LanguageCode.class);
        if (json == null || json.isEmpty()) {
            return out;
        }

        json.getMap().forEach((k, v) -> {
            if (k == null || v == null) {
                return;
            }

            LanguageCode lang = LanguageCode.valueOf(k);
            LinkedHashSet<String> set = new LinkedHashSet<>();

            if (v instanceof JsonArray ja) {
                for (int i = 0; i < ja.size(); i++) {
                    String s = Objects.toString(ja.getValue(i), "").trim();
                    if (!s.isEmpty()) {
                        set.add(s);
                    }
                }
            } else if (v instanceof Iterable<?> it) {
                for (Object item : it) {
                    String s = Objects.toString(item, "").trim();
                    if (!s.isEmpty()) {
                        set.add(s);
                    }
                }
            }

            if (!set.isEmpty()) {
                out.put(lang, set);
            }
        });

        return out;
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }

    public Uni<List<Listener>> findByUserDataFieldInBrand(String brandSlug, UUID excludeListenerId, String fieldName, String fieldValue) {
        String sql = "SELECT l.* FROM " + entityData.getTableName() + " l " +
                "JOIN kneobroadcaster__listener_brands lb ON l.id = lb.listener_id " +
                "JOIN kneobroadcaster__brands b ON b.id = lb.brand_id " +
                "WHERE b.slug_name = $1 AND l.id != $2 AND l.archived = 0 " +
                "AND lower(l.user_data->>$3) = lower($4) " +
                "LIMIT 5";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandSlug, excludeListenerId, fieldName, fieldValue))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Listener> findByUserDataField(String fieldName, String fieldValue) {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t WHERE t.user_data->$1 = $2 LIMIT 1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(fieldName, fieldValue))
                .onItem().transformToUni(rows -> {
                    var it = rows.iterator();
                    if (it.hasNext()) {
                        return Uni.createFrom().item(from(it.next()));
                    } else {
                        return Uni.createFrom().nullItem();
                    }
                });
    }

    public Uni<Listener> findByUserId(Long userId) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE user_id = $1 AND archived = 0";
        return client.preparedQuery(sql)
                .execute(Tuple.of(userId))
                .onItem().transformToUni(rows -> {
                    if (rows.iterator().hasNext()) {
                        return Uni.createFrom().item(from(rows.iterator().next()));
                    } else {
                        return Uni.createFrom().nullItem();
                    }
                });
    }

    private String buildFilterConditions(ListenerFilter filter) {
        return buildFilterConditions(filter, "t");
    }

    private String buildFilterConditions(ListenerFilter filter, String tableAlias) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getSearchTerm() != null && !filter.getSearchTerm().isEmpty()) {
            conditions.append(" AND ").append(tableAlias).append(".search_keywords % lower('")
                    .append(filter.getSearchTerm().replace("'", "''")).append("')");
        }

        if (filter.getCountries() != null && !filter.getCountries().isEmpty()) {
            conditions.append(" AND ").append(tableAlias).append(".country IN (");
            for (int i = 0; i < filter.getCountries().size(); i++) {
                if (i > 0) {
                    conditions.append(", ");
                }
                conditions.append("'").append(filter.getCountries().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        return conditions.toString();
    }

    public Uni<Void> updateUserData(UUID id, UserData userData) {
        JsonObject userDataJson = toUserDataJson(userData);
        Tuple params = Tuple.tuple()
                .addJsonObject(userDataJson)
                .addUUID(id);
        return client.preparedQuery("UPDATE " + entityData.getTableName() + " SET user_data=$1 WHERE id=$2")
                .execute(params)
                .onFailure().invoke(err -> LOGGER.error("Failed to update user_data for listener {}: {}", id, err.getMessage()))
                .invoke(rows -> {
                    if (rows.rowCount() == 0) {
                        LOGGER.warn("updateUserData affected 0 rows for listener id={} — RLS or missing record?", id);
                    } else {
                        LOGGER.info("updateUserData OK for listener id={}, rows={}", id, rows.rowCount());
                    }
                })
                .replaceWithVoid();
    }

    private JsonObject toUserDataJson(UserData userData) {
        JsonObject json = new JsonObject();
        if (userData == null || userData.getData() == null || userData.getData().isEmpty()) {
            return json;
        }
        userData.getData().forEach(json::put);
        return json;
    }

    private UserData fromUserDataJson(JsonObject json) {
        UserData userData = new UserData();
        if (json == null || json.isEmpty()) {
            return userData;
        }
        json.getMap().forEach((key, value) -> {
            if (key != null && value != null) {
                userData.put(key, value.toString());
            }
        });
        return userData;
    }

    public Uni<Void> updateLabels(UUID listenerId, List<UUID> labels) {
        return upsertLabels(client, listenerId, labels);
    }

    private Uni<Void> upsertLabels(SqlClient client, UUID listenerId, List<UUID> labels) {
        if (labels == null || labels.isEmpty()) {
            return client.preparedQuery("DELETE FROM kneobroadcaster__listener_labels WHERE listener_id = $1")
                    .execute(Tuple.of(listenerId))
                    .replaceWithVoid();
        }

        String deleteSql = "DELETE FROM kneobroadcaster__listener_labels WHERE listener_id = $1";
        String insertSql = "INSERT INTO kneobroadcaster__listener_labels (listener_id, label_id) VALUES ($1, $2) ON CONFLICT DO NOTHING";

        return client.preparedQuery(deleteSql)
                .execute(Tuple.of(listenerId))
                .chain(() -> Multi.createFrom().iterable(labels)
                        .onItem().transformToUni(labelId ->
                                client.preparedQuery(insertSql).execute(Tuple.of(listenerId, labelId))
                        )
                        .merge()
                        .collect().asList()
                ).replaceWithVoid();
    }

    private Uni<List<UUID>> loadLabels(UUID listenerId) {
        String sql = "SELECT label_id FROM kneobroadcaster__listener_labels WHERE listener_id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(listenerId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("label_id"))
                .collect().asList();
    }
}
