package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.dto.actions.cnst.ActionType;
import com.semantyca.core.model.embedded.DocumentAccessInfo;
import com.semantyca.core.model.scheduler.Scheduler;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.exception.DocumentHasNotFoundException;
import com.semantyca.core.repository.exception.DocumentModificationAccessException;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.mixpla.model.Event;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.EventPriority;
import com.semantyca.mixpla.model.cnst.EventType;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.EVENT;


@ApplicationScoped
public class EventRepository extends AsyncRepository implements SchedulableRepository<Event> {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(EVENT);

    @Inject
    public EventRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Event>> getAll(int limit, int offset, boolean includeArchived, IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

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
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Event> findById(UUID uuid, IUser user, boolean includeArchived) {
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
                        return from(iterator.next());
                    } else {
                        LOGGER.warn("No {} found with id: {}, user: {} ", EVENT, uuid, user.getId());
                        throw new DocumentHasNotFoundException(uuid);
                    }
                });
    }

    public Uni<List<Event>> findForBrand(String brandSlugName, int limit, int offset, IUser user, boolean includeArchived) {
        String sql = "SELECT e.* " +
                "FROM " + entityData.getTableName() + " e " +
                "JOIN " + entityData.getRlsName() + " rls ON e.id = rls.entity_id " +
                "WHERE e.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (e.archived IS NULL OR e.archived = 0)";
        }

        sql += " ORDER BY e.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandSlugName, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<Event> insert(Event event, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                OffsetDateTime nowTime = OffsetDateTime.now(ZoneOffset.UTC);

                String sql = "INSERT INTO " + entityData.getTableName() +
                        " (author, reg_date, last_mod_user, last_mod_date, brand_id, type, description, priority, archived, scheduler, stage_playlist) " +
                        "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) RETURNING id";

                Tuple params = Tuple.tuple()
                        .addLong(user.getId())
                        .addOffsetDateTime(nowTime)
                        .addLong(user.getId())
                        .addOffsetDateTime(nowTime)
                        .addUUID(event.getBrandId())
                        .addString(event.getType().toString())
                        .addString(event.getDescription())
                        .addString(event.getPriority().name())
                        .addInteger(0)
                        .addJsonObject(JsonObject.of("scheduler", JsonObject.mapFrom(event.getScheduler())))
                        .addJsonObject(event.getPlaylistRequest() != null ? JsonObject.mapFrom(event.getPlaylistRequest()) : null);

                return client.withTransaction(tx ->
                        tx.preparedQuery(sql)
                                .execute(params)
                                .onFailure().invoke(throwable -> LOGGER.error("Failed to insert event for user: {}", user.getId(), throwable))
                                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                                .onItem().transformToUni(id ->
                                        insertRLSPermissions(tx, id, entityData, user)
                                        .onItem().transformToUni(ignored -> updateActionsForEvent(tx, id, event.getScenePrompts()))
                                        .onItem().transform(ignored -> id)
                        )
                ).onItem().transformToUni(id -> findById(id, user, true));
            } catch (Exception e) {
                LOGGER.error("Failed to prepare insert parameters for event, user: {}", user.getId(), e);
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Event> update(UUID id, Event event, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onFailure().invoke(throwable -> LOGGER.error("Failed to check RLS permissions for update event: {} by user: {}", id, user.getId(), throwable))
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException(
                                        "User does not have edit permission", user.getUserName(), id));
                            }

                            OffsetDateTime nowTime = OffsetDateTime.now(ZoneOffset.UTC);

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET brand_id=$1, type=$2, description=$3, priority=$4, scheduler=$5, stage_playlist=$6, last_mod_user=$7, last_mod_date=$8 " +
                                    "WHERE id=$9";

                            Tuple params = Tuple.tuple()
                                    .addUUID(event.getBrandId())
                                    .addString(event.getType().name())
                                    .addString(event.getDescription())
                                    .addString(event.getPriority().name())
                                    .addJsonObject(JsonObject.of("scheduler", JsonObject.mapFrom(event.getScheduler())))
                                    .addJsonObject(event.getPlaylistRequest() != null ? JsonObject.mapFrom(event.getPlaylistRequest()) : null)
                                    .addLong(user.getId())
                                    .addOffsetDateTime(nowTime)
                                    .addUUID(id);

                            return client.withTransaction(tx ->
                                    tx.preparedQuery(sql)
                                            .execute(params)
                                            .onFailure().invoke(throwable -> LOGGER.error("Failed to update event: {} by user: {}", id, user.getId(), throwable))
                                            .onItem().transformToUni(rowSet -> {
                                                if (rowSet.rowCount() == 0) {
                                                    return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                                }
                                                return updateActionsForEvent(tx, id, event.getScenePrompts());
                                            })
                            ).onItem().transformToUni(ignored -> findById(id, user, true));
                        });
            } catch (Exception e) {
                LOGGER.error("Failed to prepare update parameters for event: {} by user: {}", id, user.getId(), e);
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Integer> archive(UUID id, IUser user) {
        return archive(id, entityData, user);
    }

    public Uni<Integer> delete(UUID id, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[1]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException(
                                "User does not have delete permission", user.getUserName(), id));
                    }

                    return client.withTransaction(tx -> {
                        String deleteActionsSql = "DELETE FROM mixpla__event_actions WHERE event_id = $1";
                        String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                        String deleteEntitySql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                        return tx.preparedQuery(deleteActionsSql).execute(Tuple.of(id))
                                .onItem().transformToUni(ignored ->
                                        tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id)))
                                .onItem().transformToUni(ignored ->
                                        tx.preparedQuery(deleteEntitySql).execute(Tuple.of(id)))
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    @Override
    public Uni<List<Event>> findActiveScheduled() {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE t.archived = 0 AND t.scheduler IS NOT NULL AND rls.reader = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(SuperUser.build().getId()))
                .onFailure().invoke(throwable -> LOGGER.error("Failed to retrieve active scheduled events", throwable))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .select().where(e -> e.getScheduler() != null && e.getScheduler().isEnabled())
                .collect().asList();
    }

    private Uni<Event> from(Row row) {
        Event doc = new Event();
        setDefaultFields(doc, row);
        doc.setBrandId(row.getUUID("brand_id"));
        doc.setType(EventType.valueOf(row.getString("type")));
        doc.setDescription(row.getString("description"));
        doc.setPriority(EventPriority.valueOf(row.getString("priority")));
        doc.setArchived(row.getInteger("archived"));

        JsonObject scheduleJson = row.getJsonObject("scheduler");
        if (scheduleJson != null) {
            try {
                JsonObject scheduleData = scheduleJson.getJsonObject("scheduler");
                if (scheduleData != null) {
                    Scheduler schedule = mapper.convertValue(scheduleData.getMap(), Scheduler.class);
                    doc.setScheduler(schedule);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to parse scheduler JSON for event: {}", row.getUUID("id"), e);
            }
        }

        JsonObject stagePlaylistJson = row.getJsonObject("stage_playlist");
        if (stagePlaylistJson != null) {
            try {
                PlaylistRequest playlistRequest = mapper.convertValue(stagePlaylistJson.getMap(), PlaylistRequest.class);
                doc.setPlaylistRequest(playlistRequest);
            } catch (Exception e) {
                LOGGER.error("Failed to parse stage_playlist JSON for event: {}", row.getUUID("id"), e);
            }
        }

        return getActionsForEvent(doc.getId())
                .onItem().transform(actions -> {
                    doc.setScenePrompts(actions);
                    return doc;
                });
    }

    public Uni<List<ScenePrompt>> getActionsForEvent(UUID eventId) {
        String sql = "SELECT prompt_id, action_type, rank, weight, active FROM mixpla__event_actions WHERE event_id = $1 ORDER BY rank ASC";
        return client.preparedQuery(sql)
                .execute(Tuple.of(eventId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    ScenePrompt scenePrompt = new ScenePrompt();
                    scenePrompt.setPromptId(row.getUUID("prompt_id"));
                    String actionTypeStr = row.getString("action_type");
                    if (actionTypeStr != null) {
                        scenePrompt.setActionType(ActionType.valueOf(actionTypeStr));
                    }
                    scenePrompt.setRank(row.getInteger("rank"));
                    scenePrompt.setWeight(row.getBigDecimal("weight"));
                    scenePrompt.setActive(row.getBoolean("active"));
                    return scenePrompt;
                })
                .collect().asList();
    }

    public Uni<Void> updateActionsForEvent(io.vertx.mutiny.sqlclient.SqlClient tx, UUID eventId, List<ScenePrompt> scenePrompts) {
        String deleteSql = "DELETE FROM mixpla__event_actions WHERE event_id = $1";
        if (scenePrompts == null || scenePrompts.isEmpty()) {
            return tx.preparedQuery(deleteSql)
                    .execute(Tuple.of(eventId))
                    .replaceWithVoid();
        }

        List<ScenePrompt> validScenePrompts = scenePrompts.stream()
                .filter(a -> a != null && a.getPromptId() != null)
                .toList();

        if (validScenePrompts.isEmpty()) {
            return tx.preparedQuery(deleteSql)
                    .execute(Tuple.of(eventId))
                    .replaceWithVoid();
        }

        String insertSql = "INSERT INTO mixpla__event_actions (event_id, prompt_id, action_type, rank, weight, active) VALUES ($1, $2, $3, $4, $5, $6)";
        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(eventId))
                .chain(() -> {
                    List<Tuple> batches = new ArrayList<>();
                    for (int i = 0; i < validScenePrompts.size(); i++) {
                        ScenePrompt scenePrompt = validScenePrompts.get(i);
                        batches.add(Tuple.of(
                            eventId,
                            scenePrompt.getPromptId(),
                            scenePrompt.getActionType() != null ? scenePrompt.getActionType().name() : null,
                            scenePrompt.getRank() != 0 ? scenePrompt.getRank() : i,
                            scenePrompt.getWeight(),
                            scenePrompt.isActive()
                        ));
                    }
                    return tx.preparedQuery(insertSql).executeBatch(batches);
                })
                .replaceWithVoid();
    }
}