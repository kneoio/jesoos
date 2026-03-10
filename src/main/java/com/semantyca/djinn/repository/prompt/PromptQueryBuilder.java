package com.semantyca.djinn.repository.prompt;

import com.semantyca.mixpla.model.filter.PromptFilter;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PromptQueryBuilder {

    public String buildGetAllQuery(String tableName, String rlsName, long userId, boolean includeArchived,
                                   PromptFilter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT * FROM ").append(tableName).append(" t, ").append(rlsName).append(" rls ")
                .append("WHERE t.id = rls.entity_id AND rls.reader = ").append(userId);

        if (!includeArchived) {
            sql.append(" AND t.archived = 0");
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

    String buildFilterConditions(PromptFilter filter) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getLanguageTag() != null) {
            conditions.append(" AND t.language_tag = '")
                    .append(filter.getLanguageTag().tag())
                    .append("'");
        }

        if (filter.getPromptType() != null) {
            conditions.append(" AND t.prompt_type = '")
                    .append(filter.getPromptType().name())
                    .append("'");
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
