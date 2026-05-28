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
    private final UUID aiAgentId;
    private final long userId;
    private Map<String, String> collectedVars = new HashMap<>();
    @Setter
    private String pendingVar;
    @Setter
    private String djName = "DJ";

    public AdSessionData(String brandSlug, UUID brandId, UUID aiAgentId, long userId) {
        this.brandSlug = brandSlug;
        this.brandId = brandId;
        this.aiAgentId = aiAgentId;
        this.userId = userId;
    }

    public void setCollectedVars(Map<String, String> vars) {
        this.collectedVars = new HashMap<>(vars);
    }
}
