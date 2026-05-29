package com.semantyca.jesoos.service.live.scripting;

import com.semantyca.mixpla.model.UserAd;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class UserAdHelper {

    private static final Logger LOGGER = Logger.getLogger(UserAdHelper.class);
    private static final String TABLE = "mixpla__user_ads";

    private final Pool client;
    private final UUID brandId;
    private UUID lastSelectedId;
    private String lastSelectedTitle;
    private String lastSelectedSlugName;

    public UserAdHelper(Pool client, UUID brandId) {
        this.client = client;
        this.brandId = brandId;
    }

    public UUID getLastSelectedId() {
        return lastSelectedId;
    }

    public String getLastSelectedTitle() {
        return lastSelectedTitle;
    }

    public String getLastSelectedSlugName() {
        return lastSelectedSlugName;
    }

    public List<Map<String, Object>> getAvailable() {
        try {
            String sql = "SELECT id, title, slug_name, description, contacts, user_data FROM " + TABLE +
                    " WHERE brand_id = $1 AND (archived IS NULL OR archived = 0) ORDER BY reg_date DESC LIMIT 20";
            return client.preparedQuery(sql).execute(Tuple.of(brandId))
                    .map(rows -> {
                        List<Map<String, Object>> result = new ArrayList<>();
                        rows.forEach(row -> {
                            Map<String, Object> ad = new HashMap<>();
                            ad.put("id", row.getUUID("id").toString());
                            ad.put("title", row.getString("title"));
                            ad.put("slug_name", row.getString("slug_name"));
                            ad.put("description", row.getString("description"));
                            ad.put("contacts", row.getString("contacts"));
                            var ud = row.getJsonObject("user_data");
                            if (ud != null) {
                                ud.getMap().forEach((k, v) -> ad.put(k, v != null ? v.toString() : ""));
                            }
                            result.add(ad);
                        });
                        return result;
                    })
                    .await().indefinitely();
        } catch (Exception e) {
            LOGGER.warnf("UserAdHelper.getAvailable failed: %s", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> getRandom() {
        List<Map<String, Object>> ads = getAvailable();
        if (ads.isEmpty()) return Map.of();
        Map<String, Object> ad = ads.get((int) (Math.random() * ads.size()));
        Object id = ad.get("id");
        if (id != null) {
            lastSelectedId = UUID.fromString(id.toString());
        }
        Object title = ad.get("title");
        if (title != null) {
            lastSelectedTitle = title.toString();
        }
        Object slugName = ad.get("slug_name");
        if (slugName != null) {
            lastSelectedSlugName = slugName.toString();
        }
        return ad;
    }
}
