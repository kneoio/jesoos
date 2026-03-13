package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.ScriptVariable;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.embedded.DocumentAccessInfo;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.exception.DocumentHasNotFoundException;
import com.semantyca.core.repository.exception.DocumentModificationAccessException;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.mixpla.model.BrandScript;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.cnst.SceneTimingMode;
import com.semantyca.mixpla.model.filter.ScriptFilter;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.SCRIPT;


@ApplicationScoped
public class ScriptRepository extends AsyncRepository {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SCRIPT);

    @Inject
    public ScriptRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Script>> getAll(int limit, int offset, boolean includeArchived, final IUser user, final ScriptFilter filter) {
        String sql = """
                    SELECT t.*, rls.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels
                    FROM %s t
                    JOIN %s rls ON t.id = rls.entity_id
                    WHERE rls.reader = %s
                """.formatted(entityData.getTableName(), entityData.getRlsName(), user.getId());

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

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm().trim()))
                    .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                    .onItem().transform(this::from)
                    .collect().asList();
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived) {
        return getAllCount(user, includeArchived, null);
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, ScriptFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm().trim()))
                    .onItem().transform(rows -> rows.iterator().next().getInteger(0));
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    private String buildFilterConditions(ScriptFilter filter) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            conditions.append(" AND (");
            conditions.append("t.name ILIKE '%' || $1 || '%' ");
            conditions.append("OR t.description ILIKE '%' || $1 || '%'");
            conditions.append(")");
        }

        if (filter.getLabels() != null && !filter.getLabels().isEmpty()) {
            conditions.append(" AND EXISTS (SELECT 1 FROM mixpla_script_labels sl2 WHERE sl2.script_id = t.id AND sl2.label_id IN (");
            for (int i = 0; i < filter.getLabels().size(); i++) {
                if (i > 0) {
                    conditions.append(", ");
                }
                conditions.append("'").append(filter.getLabels().get(i).toString()).append("'");
            }
            conditions.append("))");
        }

        if (filter.getTimingMode() != null) {
            conditions.append(" AND t.timing_mode = '").append(filter.getTimingMode().name()).append("'");
        }

        if (filter.getLanguageTag() != null) {
            conditions.append(" AND t.language_tag = '").append(filter.getLanguageTag().tag()).append("'");
        }

        return conditions.toString();
    }

    public Uni<List<Script>> getAllShared(int limit, int offset, final IUser user, final ScriptFilter filter) {
        String sql = """
                    SELECT t.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels
                    FROM %s t
                    WHERE (t.access_level = 1 OR EXISTS (
                        SELECT 1 FROM %s rls WHERE rls.entity_id = t.id AND rls.reader = %s
                    )) AND t.archived = 0
                """.formatted(entityData.getTableName(), entityData.getRlsName(), user.getId());

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        sql += " ORDER BY t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm().trim()))
                    .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                    .onItem().transform(this::from)
                    .collect().asList();
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllSharedCount(IUser user) {
        return getAllSharedCount(user, null);
    }

    public Uni<Integer> getAllSharedCount(IUser user, ScriptFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t " +
                "WHERE (t.access_level = 1 OR EXISTS (SELECT 1 FROM " + entityData.getRlsName() +
                " rls WHERE rls.entity_id = t.id AND rls.reader = " + user.getId() + ")) AND t.archived = 0";

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm().trim()))
                    .onItem().transform(rows -> rows.iterator().next().getInteger(0));
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Script> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = """
                    SELECT theTable.*, rls.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = theTable.id) AS labels
                    FROM %s theTable
                    JOIN %s rls ON theTable.id = rls.entity_id
                    WHERE rls.reader = $1 AND theTable.id = $2
                """.formatted(entityData.getTableName(), entityData.getRlsName());

        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), id))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                    }
                });
    }

    public Uni<Script> updateAccessLevel(UUID id, Integer accessLevel, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(
                                        new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id)
                                );
                            }

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET access_level=$1, last_mod_user=$2, last_mod_date=$3 WHERE id=$4";

                            OffsetDateTime now = OffsetDateTime.now();

                            Tuple params = Tuple.tuple()
                                    .addInteger(accessLevel)
                                    .addLong(user.getId())
                                    .addOffsetDateTime(now)
                                    .addUUID(id);

                            return client.preparedQuery(sql)
                                    .execute(params)
                                    .onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return findById(id, user, true);
                                    });
                        });
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Script> insert(Script script, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                String sql = "INSERT INTO " + entityData.getTableName() +
                        " (author, reg_date, last_mod_user, last_mod_date, name, slug_name, default_profile_id, description, access_level, language_tag, timing_mode, required_variables) " +
                        "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) RETURNING id";

                OffsetDateTime now = OffsetDateTime.now();

                JsonArray requiredVarsJson = null;
                if (script.getRequiredVariables() != null && !script.getRequiredVariables().isEmpty()) {
                    requiredVarsJson = new JsonArray(mapper.writeValueAsString(script.getRequiredVariables()));
                }

                Tuple params = Tuple.tuple()
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addString(script.getName())
                        .addString(script.getSlugName())
                        .addUUID(script.getDefaultProfileId())
                        .addString(script.getDescription())
                        .addInteger(script.getAccessLevel())
                        .addString(script.getLanguageTag().tag())
                        .addString(script.getTimingMode() != null ? script.getTimingMode().name() : SceneTimingMode.ABSOLUTE_TIME.name())
                        .addJsonArray(requiredVarsJson);

                return client.withTransaction(tx ->
                        tx.preparedQuery(sql)
                                .execute(params)
                                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                                .onItem().transformToUni(id ->
                                        upsertLabels(tx, id, script.getLabels())
                                                .onItem().transformToUni(ignored ->
                                                        insertRLSPermissions(tx, id, entityData, user)
                                                )
                                                .onItem().transform(ignored -> id)
                                )
                ).onItem().transformToUni(id -> findById(id, user, true));
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Script> update(UUID id, Script script, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                JsonArray requiredVarsJson = null;
                if (script.getRequiredVariables() != null && !script.getRequiredVariables().isEmpty()) {
                    requiredVarsJson = new JsonArray(mapper.writeValueAsString(script.getRequiredVariables()));
                }
                JsonArray finalRequiredVarsJson = requiredVarsJson;

                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(
                                        new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id)
                                );
                            }

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET name=$1, slug_name=$2, default_profile_id=$3, description=$4, language_tag=$5, timing_mode=$6, last_mod_user=$7, last_mod_date=$8, required_variables=$9 " +
                                    "WHERE id=$10";

                            OffsetDateTime now = OffsetDateTime.now();

                            Tuple params = Tuple.tuple()
                                    .addString(script.getName())
                                    .addString(script.getSlugName())
                                    .addUUID(script.getDefaultProfileId())
                                    .addString(script.getDescription())
                                    .addString(script.getLanguageTag().tag())
                                    .addString(script.getTimingMode() != null ? script.getTimingMode().name() : SceneTimingMode.ABSOLUTE_TIME.name())
                                    .addLong(user.getId())
                                    .addOffsetDateTime(now)
                                    .addJsonArray(finalRequiredVarsJson)
                                    .addUUID(id);

                            return client.withTransaction(tx ->
                                    upsertLabels(tx, id, script.getLabels())
                                            .onItem().transformToUni(ignored ->
                                                    tx.preparedQuery(sql).execute(params)
                                            )
                            ).onItem().transformToUni(rowSet -> {
                                if (rowSet.rowCount() == 0) {
                                    return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                }
                                return findById(id, user, true);
                            });
                        });
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    private Uni<Void> upsertLabels(SqlClient tx, UUID scriptId, List<UUID> labels) {
        String deleteSql = "DELETE FROM mixpla_script_labels WHERE script_id = $1";
        if (labels == null || labels.isEmpty()) {
            return tx.preparedQuery(deleteSql)
                    .execute(Tuple.of(scriptId))
                    .replaceWithVoid();
        }
        String insertSql = "INSERT INTO mixpla_script_labels (script_id, label_id) VALUES ($1, $2) ON CONFLICT DO NOTHING";
        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(scriptId))
                .chain(() -> Multi.createFrom().iterable(labels)
                        .onItem().transformToUni(labelId ->
                                tx.preparedQuery(insertSql).execute(Tuple.of(scriptId, labelId))
                        )
                        .merge()
                        .collect().asList()
                        .replaceWithVoid());
    }

    private Script from(Row row) {
        Script doc = new Script();
        setDefaultFields(doc, row);
        doc.setName(row.getString("name"));
        doc.setSlugName(row.getString("slug_name"));
        doc.setDefaultProfileId(row.getUUID("default_profile_id"));
        doc.setDescription(row.getString("description"));
        doc.setAccessLevel(row.getInteger("access_level"));
        doc.setArchived(row.getInteger("archived"));
        String lang = row.getString("language_tag");
        doc.setLanguageTag(LanguageTag.fromTag(lang));
        String timingMode = row.getString("timing_mode");
        if (timingMode != null) {
            doc.setTimingMode(SceneTimingMode.valueOf(timingMode));
        }

        Object[] arr = row.getArrayOfUUIDs("labels");
        if (arr != null && arr.length > 0) {
            List<UUID> labels = new ArrayList<>();
            for (Object o : arr) {
                labels.add((UUID) o);
            }
            doc.setLabels(labels);
        }

        JsonArray requiredVarsJson = row.getJsonArray("required_variables");
        if (requiredVarsJson != null && !requiredVarsJson.isEmpty()) {
            try {
                List<ScriptVariable> vars = mapper.readValue(requiredVarsJson.encode(), new TypeReference<>() {
                });
                doc.setRequiredVariables(vars);
            } catch (JsonProcessingException e) {
                doc.setRequiredVariables(new ArrayList<>());
            }
        }
        return doc;
    }

    public Uni<Integer> archive(UUID id, IUser user) {
        return archive(id, entityData, user);
    }

    public Uni<Integer> delete(UUID id, IUser user) {
        return delete(id, user, false);
    }

    public Uni<Integer> delete(UUID id, IUser user, boolean cascade) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[1]) {
                        return Uni.createFrom().failure(
                                new DocumentModificationAccessException("User does not have delete permission", user.getUserName(), id)
                        );
                    }

                    if (!cascade) {
                        String checkScenesSql = "SELECT COUNT(*) FROM mixpla_script_scenes WHERE script_id = $1";
                        return client.preparedQuery(checkScenesSql)
                                .execute(Tuple.of(id))
                                .onItem().transform(rows -> rows.iterator().next().getInteger(0))
                                .onItem().transformToUni(sceneCount -> {
                                    if (sceneCount != null && sceneCount > 0) {
                                        return Uni.createFrom().failure(new IllegalStateException(
                                                "Cannot delete script: it has " + sceneCount + " scene(s)"
                                        ));
                                    }
                                    return performDelete(id);
                                });
                    }

                    return performDelete(id);
                });
    }

    private Uni<Integer> performDelete(UUID id) {
        return client.withTransaction(tx -> {
            String deleteScenePromptsSql = "DELETE FROM mixpla__script_scene_actions WHERE script_scene_id IN (SELECT id FROM mixpla_script_scenes WHERE script_id = $1)";
            String deleteScenesSql = "DELETE FROM mixpla_script_scenes WHERE script_id = $1";
            String deleteLabelsSql = "DELETE FROM mixpla_script_labels WHERE script_id = $1";
            String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
            String deleteDocSql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

            return tx.preparedQuery(deleteScenePromptsSql)
                    .execute(Tuple.of(id))
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(deleteScenesSql).execute(Tuple.of(id))
                    )
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(deleteLabelsSql).execute(Tuple.of(id))
                    )
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id))
                    )
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(deleteDocSql).execute(Tuple.of(id))
                    )
                    .onItem().transform(RowSet::rowCount);
        });
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }

    public Uni<List<BrandScript>> findForBrand(UUID brandId, final int limit, final int offset,
                                               boolean includeArchived, IUser user) {
        String sql = "SELECT t.*, bs.rank, bs.active, bs.user_variables, " +
                "ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_scripts bs ON t.id = bs.script_id " +
                "WHERE bs.brand_id = $1 AND (t.access_level = 1 OR EXISTS (" +
                "SELECT 1 FROM " + entityData.getRlsName() + " rls WHERE rls.entity_id = t.id AND rls.reader = $2))";

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        sql += " ORDER BY bs.rank ASC, t.name ASC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    BrandScript brandScript = createBrandScript(row, brandId);
                    Script script = from(row);
                    brandScript.setScript(script);
                    return brandScript;
                })
                .collect().asList();
    }

    public Uni<Integer> findForBrandCount(UUID brandId, boolean includeArchived, IUser user) {
        String sql = "SELECT COUNT(*) " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_scripts bs ON t.id = bs.script_id " +
                "WHERE bs.brand_id = $1 AND (t.access_level = 1 OR EXISTS (" +
                "SELECT 1 FROM " + entityData.getRlsName() + " rls WHERE rls.entity_id = t.id AND rls.reader = $2))";

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    private BrandScript createBrandScript(Row row, UUID brandId) {
        BrandScript brandScript = new BrandScript();
        brandScript.setId(row.getUUID("id"));
        brandScript.setDefaultBrandId(brandId);
        brandScript.setRank(row.getInteger("rank"));
        brandScript.setActive(row.getBoolean("active"));

        JsonObject userVarsJson = row.getJsonObject("user_variables");
        if (userVarsJson != null && !userVarsJson.isEmpty()) {
            try {
                Map<String, Object> userVars = mapper.readValue(userVarsJson.encode(), new TypeReference<>() {
                });
                brandScript.setUserVariables(userVars);
            } catch (JsonProcessingException e) {
                brandScript.setUserVariables(null);
            }
        }
        return brandScript;
    }

    public Uni<List<BrandScript>> findForBrandByName(String brandName, final int limit, final int offset, IUser user) {
        String sql = "SELECT t.*, " +
                "ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels " +
                "FROM " + entityData.getTableName() + " t " +
                "WHERE (t.access_level = 1 OR EXISTS (" +
                "SELECT 1 FROM " + entityData.getRlsName() + " rls WHERE rls.entity_id = t.id AND rls.reader = " + user.getId() +
                ")) AND t.archived = 0" +
                " ORDER BY t.access_level ASC, t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    BrandScript brandScript = new BrandScript();
                    brandScript.setId(row.getUUID("id"));
                    Script script = from(row);
                    brandScript.setScript(script);
                    return brandScript;
                })
                .collect().asList();
    }

    public Uni<Integer> findForBrandByNameCount(String brandName, IUser user) {
        String sql = "SELECT COUNT(*) " +
                "FROM " + entityData.getTableName() + " t " +
                "WHERE (t.access_level = 1 OR EXISTS (" +
                "SELECT 1 FROM " + entityData.getRlsName() + " rls WHERE rls.entity_id = t.id AND rls.reader = " + user.getId() +
                ")) AND t.archived = 0";

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Void> patchRequiredVariables(UUID scriptId, List<ScriptVariable> requiredVariables) {
        String sql = "UPDATE " + entityData.getTableName() + " SET required_variables = $1 WHERE id = $2";
        JsonArray jsonArray = null;
        if (requiredVariables != null && !requiredVariables.isEmpty()) {
            try {
                jsonArray = new JsonArray(mapper.writeValueAsString(requiredVariables));
            } catch (JsonProcessingException e) {
                return Uni.createFrom().failure(e);
            }
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(jsonArray, scriptId))
                .replaceWithVoid();
    }

    public Uni<List<UUID>> findScriptIdsByDraftId(UUID draftId) {
        String sql = "SELECT DISTINCT ss.script_id FROM mixpla_script_scenes ss " +
                "JOIN mixpla__script_scene_actions ssa ON ssa.script_scene_id = ss.id " +
                "JOIN mixpla_prompts p ON p.id = ssa.prompt_id " +
                "WHERE p.draft_id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(draftId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("script_id"))
                .collect().asList();
    }

    public Uni<List<UUID>> findDraftIdsForScript(UUID scriptId) {
        String sql = "SELECT DISTINCT p.draft_id FROM mixpla_script_scenes ss " +
                "JOIN mixpla__script_scene_actions ssa ON ssa.script_scene_id = ss.id " +
                "JOIN mixpla_prompts p ON p.id = ssa.prompt_id " +
                "WHERE ss.script_id = $1 AND p.draft_id IS NOT NULL";
        return client.preparedQuery(sql)
                .execute(Tuple.of(scriptId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("draft_id"))
                .collect().asList();
    }
}
