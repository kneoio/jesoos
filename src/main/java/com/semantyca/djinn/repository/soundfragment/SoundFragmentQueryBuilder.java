package com.semantyca.djinn.repository.soundfragment;

import com.semantyca.djinn.model.soundfragment.SoundFragmentFilter;
import io.kneo.core.model.user.IUser;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SoundFragmentQueryBuilder {

    public String buildGetAllQuery(String tableName, String rlsName, IUser user, boolean includeArchived,
                                   SoundFragmentFilter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT t.*, rls.*");

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            sql.append(", similarity(t.search_name, $1) AS sim");
        }

        sql.append(" FROM ").append(tableName).append(" t ")
                .append("JOIN ").append(rlsName).append(" rls ON t.id = rls.entity_id ")
                .append("WHERE rls.reader = ").append(user.getId());

        if (!includeArchived) {
            sql.append(" AND t.archived = 0");
        }

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            sql.append(" ORDER BY sim DESC");
        } else {
            sql.append(" ORDER BY t.last_mod_date DESC");
        }

        if (limit > 0) {
            sql.append(String.format(" LIMIT %s OFFSET %s", limit, offset));
        }

        return sql.toString();
    }

    String buildFilterConditions(SoundFragmentFilter filter) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            conditions.append(" AND (t.search_name ILIKE '%' || $1 || '%' OR similarity(t.search_name, $1) > 0.05)");
        }

        if (filter.getGenre() != null && !filter.getGenre().isEmpty()) {
            conditions.append(" AND EXISTS (SELECT 1 FROM kneobroadcaster__sound_fragment_genres sfg2 WHERE sfg2.sound_fragment_id = t.id AND sfg2.genre_id IN (");
            for (int i = 0; i < filter.getGenre().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getGenre().get(i).toString()).append("'");
            }
            conditions.append("))");
        }

        if (filter.getLabels() != null && !filter.getLabels().isEmpty()) {
            conditions.append(" AND EXISTS (SELECT 1 FROM kneobroadcaster__sound_fragment_labels sfl WHERE sfl.id = t.id AND sfl.label_id IN (");
            for (int i = 0; i < filter.getLabels().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getLabels().get(i).toString()).append("'");
            }
            conditions.append("))");
        }

        if (filter.getSource() != null && !filter.getSource().isEmpty()) {
            conditions.append(" AND t.source IN (");
            for (int i = 0; i < filter.getSource().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getSource().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        if (filter.getType() != null && !filter.getType().isEmpty()) {
            conditions.append(" AND t.type IN (");
            for (int i = 0; i < filter.getType().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getType().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        return conditions.toString();
    }
}
