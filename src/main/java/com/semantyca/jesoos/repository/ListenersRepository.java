package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.UserData;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.embedded.DocumentAccessInfo;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.exception.DocumentHasNotFoundException;
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
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.LISTENER;


@ApplicationScoped
public class ListenersRepository extends AsyncRepository {

    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(LISTENER);

    @Inject
    public ListenersRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
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

    public Uni<Integer> findForBrandCount(String slugName, IUser user, boolean includeArchived, ListenerFilter filter) {
        String sql = "SELECT COUNT(l.id) " +
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

        return client.preparedQuery(sql)
                .execute(Tuple.of(slugName, user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
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
