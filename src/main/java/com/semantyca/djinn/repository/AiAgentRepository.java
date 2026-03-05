package com.semantyca.djinn.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import com.semantyca.mixpla.model.aiagent.TTSSetting;
import com.semantyca.mixpla.model.cnst.LlmType;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.AI_AGENT;


@ApplicationScoped
public class AiAgentRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiAgentRepository.class);
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(AI_AGENT);

    @Inject
    public AiAgentRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<AiAgent>> getAll(int limit, int offset, boolean includeArchived, IUser user) {
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
                .onItem().transform(this::from)
                .onItem().transformToUni(agent -> loadLabels(agent.getId())
                        .onItem().transform(labels -> {
                            agent.setLabels(labels);
                            return agent;
                        }))
                .merge()
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

    public Uni<AiAgent> findById(UUID uuid, IUser user, boolean includeArchived) {
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
                                .chain(agent -> loadLabels(agent.getId())
                                        .onItem().transform(labels -> {
                                            agent.setLabels(labels);
                                            return agent;
                                        }));
                    } else {
                        LOGGER.warn("No {} found with id: {}, user: {} ", AI_AGENT, uuid, user.getId());
                        throw new DocumentHasNotFoundException(uuid);
                    }
                });
    }

    private AiAgent from(Row row) {
        AiAgent doc = new AiAgent();
        setDefaultFields(doc, row);
        doc.setArchived(row.getInteger("archived"));
        doc.setName(row.getString("name"));
        doc.setCopilot(row.getUUID("copilot"));

        JsonArray preferredLangJson = row.getJsonArray("preferred_lang");
        if (preferredLangJson != null && !preferredLangJson.isEmpty()) {
            List<LanguagePreference> langPrefs = new ArrayList<>();
            for (int i = 0; i < preferredLangJson.size(); i++) {
                JsonObject prefObj = preferredLangJson.getJsonObject(i);

                LanguagePreference languagePreference = new LanguagePreference();
                languagePreference.setLanguageTag(
                        LanguageTag.fromTag(prefObj.getString("languageTag"))
                );
                languagePreference.setWeight(prefObj.getDouble("weight", 1.0));

                langPrefs.add(languagePreference);
            }
            doc.setPreferredLang(langPrefs);
        } else {
            doc.setPreferredLang(new ArrayList<>());
        }


        doc.setLlmType(LlmType.valueOf(row.getString("llm_type")));

        try {
            JsonObject ttsSettingJson = row.getJsonObject("tts_setting");
            doc.setTtsSetting(ttsSettingJson != null ? ttsSettingJson.mapTo(TTSSetting.class) : new TTSSetting());
        } catch (Exception e) {
            LOGGER.error("Failed to deserialize TTS setting for agent: {}", doc.getName(), e);
            doc.setTtsSetting(new TTSSetting());
        }

        return doc;
    }


    private Uni<List<UUID>> loadLabels(UUID aiAgentId) {
        String sql = "SELECT label_id FROM kneobroadcaster__ai_agent_labels WHERE ai_agent_id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(aiAgentId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("label_id"))
                .collect().asList();
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}
