package com.semantyca.jesoos.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.mixpla.model.stream.OtsDefinition;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.OTS_DEFINITION;

@ApplicationScoped
public class OtsDefinitionRepository extends AsyncRepository {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(OTS_DEFINITION);

    @Inject
    public OtsDefinitionRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<OtsDefinition> findBySlugName(String slugName) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE slug_name = $1 AND archived = 0";
        return client.preparedQuery(sql)
                .execute(Tuple.of(slugName))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> iterator.hasNext() ? from(iterator.next()) : null);
    }

    private OtsDefinition from(Row row) {
        OtsDefinition doc = new OtsDefinition();
        setDefaultFields(doc, row);
        doc.setSlugName(row.getString("slug_name"));
        doc.setName(row.getString("name"));
        doc.setScriptId(row.getUUID("script_id"));
        doc.setBrandId(row.getUUID("brand_id"));
        doc.setAgentId(row.getUUID("agent_id"));
        doc.setChatContext(row.getString("chat_context"));
        JsonObject vars = row.getJsonObject("user_variables");
        if (vars != null) {
            doc.setUserVariables(vars.getMap());
        }
        JsonObject durations = row.getJsonObject("scene_durations");
        if (durations != null) {
            Map<UUID, Integer> sceneDurations = new HashMap<>();
            durations.forEach(e -> sceneDurations.put(UUID.fromString(e.getKey()), ((Number) e.getValue()).intValue()));
            doc.setSceneDurations(sceneDurations);
        }
        return doc;
    }
}
