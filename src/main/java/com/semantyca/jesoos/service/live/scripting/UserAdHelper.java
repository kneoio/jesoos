package com.semantyca.jesoos.service.live.scripting;

import com.semantyca.mixpla.model.UserAd;
import io.vertx.mutiny.sqlclient.Pool;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class UserAdHelper {

    private static final Logger LOGGER = Logger.getLogger(UserAdHelper.class);
    private static final String TABLE = "mixpla__user_ads";

    private final Pool client;

    public UserAdHelper(Pool client) {
        this.client = client;
    }

    public List<Map<String, Object>> getAvailable() {
        try {
            String sql = "SELECT id, title, description, contacts, user_data FROM " + TABLE +
                    " WHERE (archived IS NULL OR archived = 0) ORDER BY reg_date DESC LIMIT 20";
            return client.query(sql).execute()
                    .map(rows -> {
                        List<Map<String, Object>> result = new ArrayList<>();
                        rows.forEach(row -> {
                            Map<String, Object> ad = new HashMap<>();
                            ad.put("id", row.getUUID("id").toString());
                            ad.put("title", row.getString("title"));
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
        return ads.get((int) (Math.random() * ads.size()));
    }
}
