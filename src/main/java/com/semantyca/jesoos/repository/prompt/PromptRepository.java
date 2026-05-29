package com.semantyca.jesoos.repository.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.exception.DocumentHasNotFoundException;
import com.semantyca.core.repository.exception.DocumentModificationAccessException;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.mixpla.model.DjPrompt;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.PromptType;
import com.semantyca.mixpla.model.filter.PromptFilter;
import com.semantyca.mixpla.repository.MixplaNameResolver;
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

    public Uni<List<DjPrompt>> getAll(int limit, int offset, final IUser user, final PromptFilter filter) {
        String sql = queryBuilder.buildGetAllQuery(
                entityData.getTableName(),
                entityData.getRlsName(),
                user.getId(),
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

    public Uni<Integer> getAllCount(IUser user, final PromptFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId() + " AND t.archived = 0";


        if (filter != null && filter.isActivated()) {
            sql += queryBuilder.buildFilterConditions(filter);
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<DjPrompt> findById(UUID id, IUser user) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2 AND theTable.archived = 0";


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

    public Uni<DjPrompt> findByMasterAndLanguage(UUID masterId, LanguageTag languageTag) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                " WHERE master_id = $1 AND language_tag = $2 AND archived = 0";


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

    public Uni<DjPrompt> insert(DjPrompt prompt, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                String sql = "INSERT INTO " + entityData.getTableName() +
                        " (author, reg_date, last_mod_user, last_mod_date, enabled, prompt, description, prompt_type, language_tag, is_master, locked, title, backup, draft_id, master_id, version) " +
                        "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16) RETURNING id";

                OffsetDateTime now = OffsetDateTime.now();

                Tuple params = Tuple.tuple()
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addBoolean(prompt.isEnabled())
                        .addString(prompt.getPrompt())
                        .addString(prompt.getDescription())
                        .addString(prompt.getPromptType() != null ? prompt.getPromptType().name() : PromptType.SONG_INTRO.name())
                        .addString(prompt.getLanguageTag().tag())
                        .addBoolean(prompt.isMaster())
                        .addBoolean(prompt.isLocked())
                        .addString(prompt.getTitle())
                        .addJsonObject(JsonObject.of("backup", prompt.getBackup()))
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
                        .onItem().transformToUni(id -> findById(id, user));
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<DjPrompt> update(UUID id, DjPrompt prompt, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                            }

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET enabled=$1, prompt=$2, description=$3, prompt_type=$4, language_tag=$5, is_master=$6, locked=$7, title=$8, backup=$9, draft_id=$10, master_id=$11, version=$12, last_mod_user=$13, last_mod_date=$14 " +
                                    "WHERE id=$15";

                            OffsetDateTime now = OffsetDateTime.now();

                            Tuple params = Tuple.tuple()
                                    .addBoolean(prompt.isEnabled())
                                    .addString(prompt.getPrompt())
                                    .addString(prompt.getDescription())
                                    .addString(prompt.getPromptType() != null ? prompt.getPromptType().name() : PromptType.SONG_INTRO.name())
                                    .addString(prompt.getLanguageTag().tag())
                                    .addBoolean(prompt.isMaster())
                                    .addBoolean(prompt.isLocked())
                                    .addString(prompt.getTitle())
                                    .addJsonObject(prompt.getBackup())
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
                                        return findById(id, user);
                                    });
                        });
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    private DjPrompt from(Row row) {
        DjPrompt doc = new DjPrompt();
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
        doc.setDraftId(row.getUUID("draft_id"));
        doc.setMasterId(row.getUUID("master_id"));
        doc.setArchived(row.getInteger("archived"));
        doc.setVersion(row.getDouble("version"));
        return doc;
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

    public Uni<List<ScenePrompt>> getPromptsForScene(UUID sceneId) {
        String sql = "SELECT prompt_id, rank, weight, active FROM mixpla__script_scene_prompts " +
                "WHERE script_scene_id = $1 AND prompt_id IS NOT NULL ORDER BY rank ASC";
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
}
