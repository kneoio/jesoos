package com.semantyca.jesoos.service.chat.ad;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
public class AdSessionData {

    private final String brandSlug;
    private final UUID brandId;
    private final Long userId;
    private Map<String, String> collectedVars = new HashMap<>();
    private Map<String, String> userData = new HashMap<>();
    @Setter
    private String pendingVar;
    @Setter
    private String djName = "DJ";

    public AdSessionData(String brandSlug, UUID brandId, long userId) {
        this.brandSlug = brandSlug;
        this.brandId = brandId;
        this.userId = userId;
    }

    public void setCollectedVars(Map<String, String> vars) {
        this.collectedVars = new HashMap<>(vars);
    }

    public void setUserData(Map<String, String> data) {
        this.userData = new HashMap<>(data);
    }
}
