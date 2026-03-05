package com.semantyca.djinn.repository.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.brand.BrandScriptEntry;
import com.semantyca.mixpla.model.brand.Owner;
import com.semantyca.mixpla.model.brand.ProfileOverriding;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.cnst.SubmissionPolicy;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.kneo.officeframe.cnst.CountryCode;
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

import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.RADIO_STATION;


@ApplicationScoped
public class BrandRepository extends AsyncRepository {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(RADIO_STATION);

    @Inject
    public BrandRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Brand>> getAll(int limit, int offset, boolean includeArchived, final IUser user, String country, String query) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(entityData.getTableName()).append(" t, ")
                .append(entityData.getRlsName()).append(" rls ")
                .append("WHERE t.id = rls.entity_id AND rls.reader = $1");

        int paramIndex = 2;
        if (!includeArchived) {
            sql.append(" AND t.archived = 0");
        }
        if (country != null && !country.isBlank()) {
            sql.append(" AND t.country = $").append(paramIndex++);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (t.search_name LIKE $").append(paramIndex)
                    .append(" OR LOWER(t.description) LIKE $").append(paramIndex + 1).append(")");
            paramIndex += 2;
        }
        sql.append(" ORDER BY t.last_mod_date DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
        }

        Tuple params = Tuple.tuple().addLong(user.getId());
        if (country != null && !country.isBlank()) {
            params.addString(country.toUpperCase());
        }
        if (query != null && !query.isBlank()) {
            String q = "%" + query.toLowerCase() + "%";
            params.addString(q);
            params.addString(q);
        }

        return client.preparedQuery(sql.toString())
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, String country, String query) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM ").append(entityData.getTableName()).append(" t, ")
                .append(entityData.getRlsName()).append(" rls ")
                .append("WHERE t.id = rls.entity_id AND rls.reader = $1");

        int paramIndex = 2;
        if (!includeArchived) {
            sql.append(" AND t.archived = 0");
        }
        if (country != null && !country.isBlank()) {
            sql.append(" AND t.country = $").append(paramIndex++);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (t.search_name LIKE $").append(paramIndex)
                    .append(" OR LOWER(t.description) LIKE $").append(paramIndex + 1).append(")");
            paramIndex += 2;
        }

        Tuple params = Tuple.tuple().addLong(user.getId());
        if (country != null && !country.isBlank()) {
            params.addString(country.toUpperCase());
        }
        if (query != null && !query.isBlank()) {
            String q = "%" + query.toLowerCase() + "%";
            params.addString(q);
            params.addString(q);
        }

        return client.preparedQuery(sql.toString())
                .execute(params)
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Brand> findById(UUID id, IUser user, boolean includeArchived) {
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

    public Uni<Brand> getBySlugName(String name) {
        String sql = "SELECT * FROM %s WHERE slug_name = $1 AND archived = 0";

        return client.preparedQuery(String.format(sql, entityData.getTableName()))
                .execute(Tuple.of(name))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(name));
                    }
                });
    }

    private Brand from(Row row) {
        Brand doc = new Brand();
        setDefaultFields(doc, row);

        JsonObject localizedNameJson = row.getJsonObject(COLUMN_LOCALIZED_NAME);
        if (localizedNameJson != null) {
            EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
            localizedNameJson.getMap().forEach((key, value) ->
                    localizedName.put(LanguageCode.valueOf(key), (String) value));
            doc.setLocalizedName(localizedName);
        }

        doc.setSlugName(row.getString("slug_name"));
        doc.setArchived(row.getInteger("archived"));
        doc.setIsTemporary(row.getInteger("is_temporary"));
        String country = row.getString("country");
        doc.setCountry(country != null ? CountryCode.valueOf(country) : null);
        doc.setManagedBy(ManagedBy.valueOf(row.getString("managing_mode")));
        doc.setTimeZone(java.time.ZoneId.of(row.getString("time_zone")));
        doc.setColor(row.getString("color"));
        doc.setDescription(row.getString("description"));
        doc.setOneTimeStreamPolicy(SubmissionPolicy.valueOf(row.getString("one_time_stream_policy")));
        doc.setSubmissionPolicy(SubmissionPolicy.valueOf(row.getString("submission_policy")));
        doc.setMessagingPolicy(SubmissionPolicy.valueOf(row.getString("messaging_policy")));
        doc.setTitleFont(row.getString("title_font"));

        JsonArray bitRateJson = row.getJsonArray("bit_rate");
        if (bitRateJson != null && !bitRateJson.isEmpty()) {
            doc.setBitRate(Long.parseLong(bitRateJson.getString(0)));
        } else {
            doc.setBitRate(128000);
        }

        JsonObject aiOverridingJson = row.getJsonObject("ai_overriding");
        if (!aiOverridingJson.isEmpty()) {
            try {
                AiOverriding ai = mapper.treeToValue(
                        mapper.valueToTree(aiOverridingJson.getMap()), AiOverriding.class);
                doc.setAiOverriding(ai);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        JsonObject profileOverridingJson = row.getJsonObject("profile_overriding");
        if (!profileOverridingJson.isEmpty()) {
            try {
                ProfileOverriding profile = mapper.treeToValue(
                        mapper.valueToTree(profileOverridingJson.getMap()), ProfileOverriding.class);
                doc.setProfileOverriding(profile);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        UUID aiAgentId = row.getUUID("ai_agent_id");
        if (aiAgentId != null) {
            doc.setAiAgentId(aiAgentId);
        }
        doc.setPopularityRate(row.getDouble("popularity_rate"));
        UUID profileId = row.getUUID("profile_id");
        if (profileId != null) {
            doc.setProfileId(profileId);
        }

        JsonObject ownerJson = row.getJsonObject("owner");
        if (ownerJson != null && !ownerJson.isEmpty()) {
            doc.setOwner(mapper.convertValue(ownerJson.getMap(), Owner.class));
        }

        return doc;
    }
    public Uni<List<BrandScriptEntry>> getScriptEntriesForBrand(UUID brandId) {
        String sql = "SELECT script_id, user_variables FROM kneobroadcaster__brand_scripts WHERE brand_id = $1 ORDER BY rank";
        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    BrandScriptEntry entry = new BrandScriptEntry();
                    entry.setScriptId(row.getUUID("script_id"));
                    JsonObject userVarsJson = row.getJsonObject("user_variables");
                    if (userVarsJson != null) {
                        entry.setUserVariables(userVarsJson.getMap());
                    }
                    return entry;
                })
                .collect().asList();
    }
}
