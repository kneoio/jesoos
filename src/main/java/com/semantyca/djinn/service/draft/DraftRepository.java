package com.semantyca.djinn.service.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.DraftFilterDTO;
import com.semantyca.djinn.repository.MixplaNameResolver;
import com.semantyca.mixpla.model.Draft;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
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

import static com.semantyca.djinn.repository.MixplaNameResolver.DRAFT;

@ApplicationScoped
public class DraftRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftRepository.class);
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(DRAFT);

    private final DraftQueryBuilder queryBuilder;

    @Inject
    public DraftRepository(PgPool client, ObjectMapper mapper, DraftQueryBuilder queryBuilder) {
        super(client, mapper, null);
        this.queryBuilder = queryBuilder;
    }

    public Uni<Draft> findByMasterAndLanguage(UUID masterId, LanguageTag languageTag, boolean includeArchived) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE master_id = $1 AND language_code = $2";
        if (!includeArchived) {
            sql += " AND archived = 0 ";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(masterId, languageTag.name()))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> iterator.hasNext() ? from(iterator.next()) : null);
    }

    public Uni<List<Draft>> getAll(int limit, int offset, boolean includeArchived, final IUser user, final DraftFilterDTO filter) {
        String sql = queryBuilder.buildGetAllQuery(
                entityData.getTableName(),
                includeArchived,
                filter,
                limit,
                offset
        );

        return client.query(sql)
                .execute()
                .onFailure().invoke(throwable -> LOGGER.error("Failed to retrieve drafts", throwable))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, final DraftFilterDTO filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t";

        if (!includeArchived) {
            sql += " WHERE (t.archived IS NULL OR t.archived = 0)";
        }

        if (filter != null && filter.isActivated()) {
            sql += queryBuilder.buildFilterConditions(filter);
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Draft> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";

        if (!includeArchived) {
            sql += " AND archived = 0";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                    }
                });
    }

    private Draft from(Row row) {
        Draft doc = new Draft();
        setDefaultFields(doc, row);

        doc.setTitle(row.getString("title"));
        doc.setContent(row.getString("content"));
        doc.setDescription(row.getString("description"));
        doc.setArchived(row.getInteger("archived"));
        doc.setEnabled(row.getBoolean("enabled"));
        doc.setMaster(row.getBoolean("is_master"));
        doc.setLocked(row.getBoolean("locked"));
        UUID masterId = row.getUUID("master_id");
        if (masterId != null) {
            doc.setMasterId(masterId);
        }

        String languageCodeStr = row.getString("language_tag");
        if (languageCodeStr != null) {
            doc.setLanguageTag(LanguageTag.fromTag(languageCodeStr));
        }

        doc.setVersion(row.getDouble("version"));

        return doc;
    }
}
