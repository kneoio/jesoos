package com.semantyca.jesoos.service.chat;

import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatState extends AgentState {

    public static final String USER_ID          = "userId";
    public static final String CONNECTION_ID    = "connectionId";
    public static final String BRAND_NAME       = "brandName";
    public static final String HISTORY          = "history";
    public static final String SYSTEM_PROMPT    = "systemPrompt";
    public static final String DJ_NAME          = "djName";
    public static final String DJ_LANGUAGES     = "djLanguages";
    public static final String TOOL_CALL        = "toolCall";
    public static final String BOT_RESPONSE     = "botResponse";
    public static final String CONTEXT_BLOCK    = "contextBlock";
    public static final String ITERATION        = "iteration";
    public static final String SESSION_TOKEN    = "sessionToken";
    public static final String SESSION_USER_NAME = "sessionUserName";

    public ChatState(Map<String, Object> initData) {
        super(initData);
    }

    public long userId() {
        Object v = data().get(USER_ID);
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        return 0L;
    }

    public String connectionId()  { return (String) data().getOrDefault(CONNECTION_ID, ""); }
    public String brandName()     { return (String) data().getOrDefault(BRAND_NAME, ""); }
    public String systemPrompt()  { return (String) data().getOrDefault(SYSTEM_PROMPT, ""); }
    public String djName()        { return (String) data().getOrDefault(DJ_NAME, ""); }
    public String djLanguages()   { return (String) data().getOrDefault(DJ_LANGUAGES, ""); }
    public LlmToolCall toolCall()   { return (LlmToolCall) data().get(TOOL_CALL); }
    public String botResponse()    { return (String) data().get(BOT_RESPONSE); }
    public String contextBlock()   { return (String) data().getOrDefault(CONTEXT_BLOCK, ""); }
    public String sessionToken()    { return (String) data().get(SESSION_TOKEN); }
    public String sessionUserName() { return (String) data().get(SESSION_USER_NAME); }
    public int iteration()        {
        Object v = data().getOrDefault(ITERATION, 0);
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    public List<LlmMessage> history() {
        Object v = data().get(HISTORY);
        if (v instanceof List<?> list) return (List<LlmMessage>) list;
        return new ArrayList<>();
    }
}
