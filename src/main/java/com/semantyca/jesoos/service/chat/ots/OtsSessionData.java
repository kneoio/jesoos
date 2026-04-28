package com.semantyca.jesoos.service.chat.ots;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OtsSessionData {

    private final String brandSlug;
    private final UUID scriptId;
    private final String scriptName;
    private final List<String> varNames;
    private final Map<String, String> varDescriptions;
    private Map<String, String> collectedVars = new HashMap<>();
    private String pendingVarName;

    public OtsSessionData(String brandSlug, UUID scriptId, String scriptName,
                          List<String> varNames, Map<String, String> varDescriptions) {
        this.brandSlug = brandSlug;
        this.scriptId = scriptId;
        this.scriptName = scriptName;
        this.varNames = new ArrayList<>(varNames);
        this.varDescriptions = new HashMap<>(varDescriptions);
    }

    public String getBrandSlug() { return brandSlug; }
    public UUID getScriptId() { return scriptId; }
    public String getScriptName() { return scriptName; }
    public List<String> getVarNames() { return varNames; }
    public Map<String, String> getVarDescriptions() { return varDescriptions; }
    public Map<String, String> getCollectedVars() { return collectedVars; }
    public String getPendingVarName() { return pendingVarName; }

    public void setPendingVarName(String name) { this.pendingVarName = name; }
    public void setCollectedVars(Map<String, String> vars) { this.collectedVars = new HashMap<>(vars); }

    public boolean isComplete() {
        return varNames.stream().allMatch(v -> collectedVars.containsKey(v) && !collectedVars.get(v).isBlank());
    }

    public String getNextMissingVar() {
        return varNames.stream()
                .filter(v -> !collectedVars.containsKey(v) || collectedVars.get(v).isBlank())
                .findFirst()
                .orElse(null);
    }
}
