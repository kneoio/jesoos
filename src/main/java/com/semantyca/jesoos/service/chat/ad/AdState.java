package com.semantyca.jesoos.service.chat.ad;

import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdState extends AgentState {

    public static final String USER_MESSAGE    = "userMessage";
    public static final String PENDING_VAR     = "pendingVar";
    public static final String COLLECTED_VARS  = "collectedVars";
    public static final String REQUIRED_VARS   = "requiredVars";
    public static final String ACTION          = "action";
    public static final String NEXT_QUESTION   = "nextQuestion";
    public static final String SAVED_AD_ID     = "savedAdId";
    public static final String USER_DATA       = "userData";
    public static final String BRAND_SLUG      = "brandSlug";

    public AdState(Map<String, Object> initData) {
        super(initData);
    }

    public String brandSlug()     { return this.<String>value(BRAND_SLUG).orElse(""); }
    public String userMessage()   { return this.<String>value(USER_MESSAGE).orElse(""); }
    public String pendingVar()    { return this.<String>value(PENDING_VAR).orElse(null); }
    public String action()        { return this.<String>value(ACTION).orElse("ask"); }
    public String nextQuestion()  { return this.<String>value(NEXT_QUESTION).orElse(""); }
    public String savedAdId()     { return this.<String>value(SAVED_AD_ID).orElse(null); }

    @SuppressWarnings("unchecked")
    public Map<String, String> collectedVars() {
        return (Map<String, String>) this.data().getOrDefault(COLLECTED_VARS, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> userData() {
        return (Map<String, String>) this.data().getOrDefault(USER_DATA, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    public List<String> requiredVars() {
        return (List<String>) this.data().getOrDefault(REQUIRED_VARS, new ArrayList<>());
    }
}
