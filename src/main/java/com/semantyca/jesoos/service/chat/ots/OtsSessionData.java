package com.semantyca.jesoos.service.chat.ots;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
public class OtsSessionData {

    private final String brandSlug;
    private final UUID scriptId;
    private final String scriptName;
    private final List<String> varNames;
    private final Map<String, String> varDescriptions;
    private Map<String, String> collectedVars = new HashMap<>();
    @Setter
    private String pendingVarName;
    @Setter
    private String djName = "DJ";

    public OtsSessionData(String brandSlug, UUID scriptId, String scriptName,
                          List<String> varNames, Map<String, String> varDescriptions) {
        this.brandSlug = brandSlug;
        this.scriptId = scriptId;
        this.scriptName = scriptName;
        this.varNames = new ArrayList<>(varNames);
        this.varDescriptions = new HashMap<>(varDescriptions);
    }

    public void setCollectedVars(Map<String, String> vars) {
        this.collectedVars = new HashMap<>(vars);
    }
}
