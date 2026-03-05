package com.semantyca.djinn.service.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.PromptFilterDTO;
import com.semantyca.djinn.repository.MixplaNameResolver;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.PromptType;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static com.semantyca.djinn.repository.MixplaNameResolver.PROMPT;

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

}
