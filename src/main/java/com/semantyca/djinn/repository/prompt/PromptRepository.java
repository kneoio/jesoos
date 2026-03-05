package com.semantyca.djinn.repository.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.PromptFilterDTO;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.PromptType;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.PROMPT;


@ApplicationScoped
public class PromptRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PromptRepository.class);
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(PROMPT);
    private final PromptQueryBuilder queryBuilder;

    @Inject
    public PromptRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository, PromptQueryBuilder queryBuilder) {
        super(client, mapper, rlsRepository);
        this.queryBuilder = queryBuilder;
    }

    public Uni<List<Prompt>> getAll(int limit, int offset, boolean includeArchived, final IUser user, final PromptFilterDTO filter) {
        String sql = queryBuilder.buildGetAllQuery(
                entityData.getTableName(),
                entityData.getRlsName(),
                user.getId(),
                includeArchived,
                filter,
                limit,
                offset
        );

        return client.query(sql)
                .execute()
                .onFailure().invoke(throwable -> LOGGER.error("Failed to retrieve prompts for user: {}", user.getId(), throwable))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, final PromptFilterDTO filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += queryBuilder.buildFilterConditions(filter);
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Prompt> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2";

        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
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

    public Uni<List<Prompt>> findByIds(List<UUID> ids, IUser user) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        String placeholders = ids.stream()
                .map(id -> "$" + (ids.indexOf(id) + 2))
                .collect(java.util.stream.Collectors.joining(","));

        String sql = "SELECT theTable.* " +
                "FROM " + entityData.getTableName() + " theTable " +
                "JOIN " + entityData.getRlsName() + " rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id IN (" + placeholders + ") AND theTable.archived = 0";

        Tuple params = Tuple.tuple().addLong(user.getId());
        for (UUID id : ids) {
            params.addUUID(id);
        }

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Prompt> findByMasterAndLanguage(UUID masterId, LanguageTag languageTag, boolean includeArchived) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                " WHERE master_id = $1 AND language_tag = $2";


        if (!includeArchived) {
            sql += " AND archived = 0";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(masterId, languageTag.tag()))
                .onFailure().transform(e -> e)
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()));
                    } else {
                        return Uni.createFrom().nullItem();
                    }
                });

    }

    public Uni<Prompt> insert(Prompt prompt, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                String sql = "INSERT INTO " + entityData.getTableName() +
                        " (author, reg_date, last_mod_user, last_mod_date, enabled, prompt, description, prompt_type, language_tag, is_master, locked, title, backup, podcast, draft_id, master_id, version) " +
                        "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17) RETURNING id";

                OffsetDateTime now = OffsetDateTime.now();

                Tuple params = Tuple.tuple()
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addBoolean(prompt.isEnabled())
                        .addString(prompt.getPrompt())
                        .addString(prompt.getDescription())
                        .addString(prompt.getPromptType() != null ? prompt.getPromptType().name() : "SONG")
                        .addString(prompt.getLanguageTag().tag())
                        .addBoolean(prompt.isMaster())
                        .addBoolean(prompt.isLocked())
                        .addString(prompt.getTitle())
                        .addJsonObject(JsonObject.of("backup", prompt.getBackup()))
                        .addBoolean(prompt.isPodcast())
                        .addUUID(prompt.getDraftId())
                        .addUUID(prompt.getMasterId())
                        .addDouble(prompt.getVersion());

                return client.withTransaction(tx ->
                                tx.preparedQuery(sql)
                                        .execute(params)
                                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                                        .onItem().transformToUni(id ->
                                                insertRLSPermissions(tx, id, entityData, user)
                                                        .onItem().transform(ignored -> id)
                                        )
                        )
                        .onItem().transformToUni(id -> findById(id, user, true));
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Prompt> update(UUID id, Prompt prompt, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                            }

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET enabled=$1, prompt=$2, description=$3, prompt_type=$4, language_tag=$5, is_master=$6, locked=$7, title=$8, backup=$9, podcast=$10, draft_id=$11, master_id=$12, version=$13, last_mod_user=$14, last_mod_date=$15 " +
                                    "WHERE id=$16";

                            OffsetDateTime now = OffsetDateTime.now();

                            Tuple params = Tuple.tuple()
                                    .addBoolean(prompt.isEnabled())
                                    .addString(prompt.getPrompt())
                                    .addString(prompt.getDescription())
                                    .addString(prompt.getPromptType() != null ? prompt.getPromptType().name() : "SONG")
                                    .addString(prompt.getLanguageTag().tag())
                                    .addBoolean(prompt.isMaster())
                                    .addBoolean(prompt.isLocked())
                                    .addString(prompt.getTitle())
                                    .addJsonObject(prompt.getBackup())
                                    .addBoolean(prompt.isPodcast())
                                    .addUUID(prompt.getDraftId())
                                    .addUUID(prompt.getMasterId())
                                    .addDouble(prompt.getVersion())
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

    private Prompt from(Row row) {
        Prompt doc = new Prompt();
        setDefaultFields(doc, row);
        doc.setEnabled(row.getBoolean("enabled"));
        doc.setPrompt(row.getString("prompt"));
        doc.setDescription(row.getString("description"));
        doc.setPromptType(PromptType.valueOf(row.getString("prompt_type")));
        doc.setLanguageTag(LanguageTag.fromTag(row.getString("language_tag")));
        doc.setMaster(row.getBoolean("is_master"));
        doc.setLocked(row.getBoolean("locked"));
        doc.setTitle(row.getString("title"));
        doc.setBackup(row.getJsonObject("backup"));
        doc.setPodcast(row.getBoolean("podcast"));
        doc.setDraftId(row.getUUID("draft_id"));
        doc.setMasterId(row.getUUID("master_id"));
        doc.setArchived(row.getInteger("archived"));
        doc.setVersion(row.getDouble("version"));
        return doc;
    }

    public Uni<Integer> archive(UUID id, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }

                    // Check if prompt is used in any scenes
                    String checkUsageSql = "SELECT COUNT(*) FROM mixpla__script_scene_actions WHERE prompt_id = $1";
                    return client.preparedQuery(checkUsageSql)
                            .execute(Tuple.of(id))
                            .onItem().transformToUni(rows -> {
                                int usageCount = rows.iterator().next().getInteger(0);
                                if (usageCount > 0) {
                                    return Uni.createFrom().failure(
                                            new IllegalStateException("Cannot archive prompt: it is currently used in " + usageCount + " scene(s)")
                                    );
                                }

                                String sql = String.format("UPDATE %s SET archived = 1, last_mod_user = $1, last_mod_date = $2 WHERE id = $3", entityData.getTableName());
                                OffsetDateTime now = OffsetDateTime.now();

                                return client.preparedQuery(sql)
                                        .execute(Tuple.of(user.getId(), now, id))
                                        .onItem().transform(RowSet::rowCount);
                            });
                });
    }

    public Uni<Integer> delete(UUID id, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[1]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have delete permission", user.getUserName(), id));
                    }

                    return client.withTransaction(tx -> {
                        String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                        String deleteDocSql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                        return tx.preparedQuery(deleteRlsSql)
                                .execute(Tuple.of(id))
                                .onItem().transformToUni(ignored ->
                                        tx.preparedQuery(deleteDocSql)
                                                .execute(Tuple.of(id))
                                )
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }

    public Uni<List<ScenePrompt>> getPromptsForScene(UUID sceneId) {
        String sql = "SELECT prompt_id, rank, weight, active FROM mixpla__script_scene_actions WHERE script_scene_id = $1 AND prompt_id IS NOT NULL ORDER BY rank ASC";
        return client.preparedQuery(sql)
                .execute(Tuple.of(sceneId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    ScenePrompt scenePrompt = new ScenePrompt();
                    scenePrompt.setPromptId(row.getUUID("prompt_id"));
                    scenePrompt.setRank(row.getInteger("rank"));
                    scenePrompt.setWeight(row.getBigDecimal("weight"));
                    scenePrompt.setActive(row.getBoolean("active"));
                    return scenePrompt;
                })
                .collect().asList();
    }

    public Uni<Void> updatePromptsForScene(io.vertx.mutiny.sqlclient.SqlClient tx, UUID sceneId, List<ScenePrompt> prompts) {
        String deleteSql = "DELETE FROM mixpla__script_scene_actions WHERE script_scene_id = $1";
        if (prompts == null || prompts.isEmpty()) {
            return tx.preparedQuery(deleteSql)
                    .execute(Tuple.of(sceneId))
                    .replaceWithVoid();
        }

        List<ScenePrompt> validPrompts = prompts.stream()
                .filter(p -> p != null && p.getPromptId() != null)
                .toList();

        if (validPrompts.isEmpty()) {
            return tx.preparedQuery(deleteSql)
                    .execute(Tuple.of(sceneId))
                    .replaceWithVoid();
        }

        String insertSql = "INSERT INTO mixpla__script_scene_actions (script_scene_id, prompt_id, rank, weight, active) VALUES ($1, $2, $3, $4, $5)";
        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(sceneId))
                .chain(() -> {
                    List<Tuple> batches = new java.util.ArrayList<>();
                    for (int i = 0; i < validPrompts.size(); i++) {
                        ScenePrompt prompt = validPrompts.get(i);
                        batches.add(Tuple.of(
                                sceneId,
                                prompt.getPromptId(),
                                prompt.getRank() != 0 ? prompt.getRank() : i,
                                prompt.getWeight(),
                                prompt.isActive()
                        ));
                    }
                    return tx.preparedQuery(insertSql).executeBatch(batches);
                })
                .replaceWithVoid();
    }
}
