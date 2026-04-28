package com.semantyca.jesoos.service.chat.ots;

import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * State for OtsGraph.
 *
 * Only JDK types (String, ArrayList, HashMap) — never custom application classes.
 * LangGraph4j serializes state via ObjectInputStream in its own classloader.
 */
public class OtsState extends AgentState {

    public static final String USER_MESSAGE     = "userMessage";
    public static final String PENDING_VAR_NAME = "pendingVarName";
    public static final String COLLECTED_VARS   = "collectedVars";
    public static final String REQUIRED_VAR_NAMES = "requiredVarNames";
    public static final String VAR_DESCRIPTIONS = "varDescriptions";
    public static final String BRAND_SLUG       = "brandSlug";
    public static final String SCRIPT_ID        = "scriptId";
    public static final String SCRIPT_NAME      = "scriptName";
    public static final String DJ_NAME          = "djName";
    public static final String NEXT_PENDING_VAR = "nextPendingVar";
    public static final String NEXT_QUESTION    = "nextQuestion";
    public static final String MIXPLA_URL       = "mixplaUrl";
    public static final String ACTION           = "action";

    public OtsState(Map<String, Object> initData) {
        super(initData);
    }

    public String userMessage()     { return this.<String>value(USER_MESSAGE).orElse(""); }
    public String pendingVarName()  { return this.<String>value(PENDING_VAR_NAME).orElse(null); }
    public String brandSlug()       { return this.<String>value(BRAND_SLUG).orElse(""); }
    public String scriptId()        { return this.<String>value(SCRIPT_ID).orElse(""); }
    public String scriptName()      { return this.<String>value(SCRIPT_NAME).orElse(""); }
    public String djName()          { return this.<String>value(DJ_NAME).orElse("DJ"); }
    public String nextPendingVar()  { return this.<String>value(NEXT_PENDING_VAR).orElse(null); }
    public String nextQuestion()    { return this.<String>value(NEXT_QUESTION).orElse(""); }
    public String mixplaUrl()       { return this.<String>value(MIXPLA_URL).orElse(""); }
    public String action()          { return this.<String>value(ACTION).orElse("ask"); }

    @SuppressWarnings("unchecked")
    public Map<String, String> collectedVars() {
        return (Map<String, String>) this.data().getOrDefault(COLLECTED_VARS, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    public List<String> requiredVarNames() {
        return (List<String>) this.data().getOrDefault(REQUIRED_VAR_NAMES, new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> varDescriptions() {
        return (Map<String, String>) this.data().getOrDefault(VAR_DESCRIPTIONS, new HashMap<>());
    }
}
