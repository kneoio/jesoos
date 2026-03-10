package com.semantyca.djinn.service.draft;

import com.semantyca.mixpla.model.filter.DraftFilter;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DraftQueryBuilder {

    public String buildGetAllQuery(String tableName, boolean includeArchived,
                                   DraftFilter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT * FROM ").append(tableName).append(" t");

        if (!includeArchived) {
            sql.append(" WHERE t.archived = 0");
        }

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        sql.append(" ORDER BY t.last_mod_date DESC");

        if (limit > 0) {
            sql.append(String.format(" LIMIT %s OFFSET %s", limit, offset));
        }

        return sql.toString();
    }

    String buildFilterConditions(DraftFilter filter) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getLanguageTag() != null) {
            conditions.append(" AND t.language_tag = '")
                    .append(filter.getLanguageTag().tag())
                    .append("'");
        }

        if (filter.getArchived() != null) {
            conditions.append(" AND t.archived = ")
                    .append(filter.getArchived());
        }

        if (filter.isEnabled()) {
            conditions.append(" AND t.enabled = true");
        }

        if (filter.isMaster()) {
            conditions.append(" AND t.is_master = true");
        }

        if (filter.isLocked()) {
            conditions.append(" AND t.locked = true");
        }

        return conditions.toString();
    }
}
