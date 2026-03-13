package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.embedded.DocumentAccessInfo;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.exception.DocumentHasNotFoundException;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.jesoos.repository.prompt.PromptRepository;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.filter.SceneFilter;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.SCRIPT_SCENE;


@ApplicationScoped
public class SceneRepository extends AsyncRepository {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SCRIPT_SCENE);
    private final PromptRepository promptRepository;

    @Inject
    public SceneRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository, PromptRepository promptRepository) {
        super(client, mapper, rlsRepository);
        this.promptRepository = promptRepository;
    }

    public Uni<List<Scene>> getAll(int limit, int offset, boolean includeArchived, IUser user, SceneFilter filter) {
        String sql = "SELECT t.*, s.name as script_title FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls, mixpla_scripts s " +
                "WHERE t.id = rls.entity_id AND t.script_id = s.id AND rls.reader = $1";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        if (filter != null && filter.isActivated() && filter.getTimingMode() != null) {
            sql += " AND s.timing_mode = '" + filter.getTimingMode().name() + "'";
        }
        if (filter != null && filter.isActivated() && filter.getScriptId() != null) {
            sql += " AND t.script_id = '" + filter.getScriptId() + "'";
        }
        sql += " ORDER BY s.name ASC, t.seq_num ASC, t.start_time ASC";
        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::fromViewEntry)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, SceneFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls, mixpla_scripts s " +
                "WHERE t.id = rls.entity_id AND t.script_id = s.id AND rls.reader = $1";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        if (filter != null && filter.isActivated() && filter.getTimingMode() != null) {
            sql += " AND s.timing_mode = '" + filter.getTimingMode().name() + "'";
        }
        if (filter != null && filter.isActivated() && filter.getScriptId() != null) {
            sql += " AND t.script_id = '" + filter.getScriptId() + "'";
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    // Per-script listing
    public Uni<List<Scene>> listByScript(UUID scriptId, int limit, int offset, boolean includeArchived, IUser user) {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = $1 AND t.script_id = $2";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        sql += " ORDER BY t.seq_num ASC, t.start_time ";
        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), scriptId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList()
                .onItem().transformToUni(scenes -> {
                    if (scenes.isEmpty()) {
                        return Uni.createFrom().item(scenes);
                    }
                    List<Uni<Scene>> sceneUnis = scenes.stream()
                            .map(scene -> promptRepository.getPromptsForScene(scene.getId())
                                    .onItem().transform(promptIds -> {
                                        scene.setIntroPrompts(promptIds);
                                        return scene;
                                    }))
                            .collect(java.util.stream.Collectors.toList());
                    return Uni.join().all(sceneUnis).andFailFast();
                });
    }

    public Uni<Integer> countByScript(UUID scriptId, boolean includeArchived, IUser user) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = $1 AND t.script_id = $2";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), scriptId))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Scene> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* FROM %s theTable JOIN %s rls ON theTable.id = rls.entity_id WHERE rls.reader = $1 AND theTable.id = $2";
        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }
        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    } else {
                        throw new DocumentHasNotFoundException(id);
                    }
                })
                .onItem().transformToUni(scene ->
                        promptRepository.getPromptsForScene(id)
                                .onItem().transform(promptIds -> {
                                    scene.setIntroPrompts(promptIds);
                                    return scene;
                                })
                );
    }

    private Scene fromViewEntry(Row row) {
        Scene doc = from(row);
        String scriptTitle = row.getString("script_title");
        if (scriptTitle != null) {
            doc.setScriptTitle(scriptTitle);
        }
        return doc;
    }

    private Scene from(Row row) {
        Scene doc = new Scene();
        setDefaultFields(doc, row);
        doc.setScriptId(row.getUUID("script_id"));
        doc.setTitle(row.getString("title"));
        doc.setArchived(row.getInteger("archived"));
        JsonArray startTimeJson = row.getJsonArray("start_time");
        if (startTimeJson != null && !startTimeJson.isEmpty()) {
            try {
                List<LocalTime> startTimes = mapper.readValue(startTimeJson.encode(), new com.fasterxml.jackson.core.type.TypeReference<List<LocalTime>>() {});
                doc.setStartTime(startTimes);
            } catch (Exception e) {
                doc.setStartTime(List.of());
            }
        } else {
            doc.setStartTime(List.of());
        }
        doc.setDurationSeconds(row.getInteger("duration_seconds"));
        Integer seqNum = row.getInteger("seq_num");
        if (seqNum != null) doc.setSeqNum(seqNum);
        doc.setOneTimeRun(row.getBoolean("one_time_run"));
        Double talk = row.getDouble("talkativity");
        if (talk != null) doc.setTalkativity(talk);
        Object[] weekdaysArr = row.getArrayOfIntegers("weekdays");
        if (weekdaysArr != null && weekdaysArr.length > 0) {
            List<Integer> weekdays = new ArrayList<>();
            for (Object o : weekdaysArr) {
                weekdays.add((Integer) o);
            }
            doc.setWeekdays(weekdays);
        }
        JsonObject stagePlaylistJson = row.getJsonObject("stage_playlist");
        if (stagePlaylistJson != null) {
            try {
                PlaylistRequest playlistRequest = mapper.convertValue(stagePlaylistJson.getMap(), PlaylistRequest.class);
                doc.setPlaylistRequest(playlistRequest);
            } catch (Exception e) {
                LOGGER.error("Failed to parse stage_playlist JSON for scene: {}", row.getUUID("id"), e);
            }
        }
        return doc;
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}
