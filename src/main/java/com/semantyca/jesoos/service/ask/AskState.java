package com.semantyca.jesoos.service.ask;

import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AskState extends AgentState {

    public static final String USER_ID = "userId";
    public static final String CONNECTION_ID = "connectionId";
    public static final String HISTORY = "history";
    public static final String SYSTEM_PROMPT = "systemPrompt";
    public static final String ASSISTANT_NAME = "assistantName";
    public static final String TOOL_CALL = "toolCall";
    public static final String BOT_RESPONSE = "botResponse";
    public static final String RESPONSE_STREAMED = "responseStreamed";
    public static final String ITERATION = "iteration";
    public static final String SESSION_TOKEN = "sessionToken";
    public static final String SESSION_USER_NAME = "sessionUserName";

    public AskState(Map<String, Object> initData) {
        super(initData);
    }

    public long userId() {
        Object v = data().get(USER_ID);
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        return 0L;
    }

    public String connectionId() { return (String) data().getOrDefault(CONNECTION_ID, ""); }
    public String systemPrompt() { return (String) data().getOrDefault(SYSTEM_PROMPT, ""); }
    public String assistantName() { return (String) data().getOrDefault(ASSISTANT_NAME, "Mixpla Ask"); }
    public LlmToolCall toolCall() { return (LlmToolCall) data().get(TOOL_CALL); }
    public String botResponse() { return (String) data().get(BOT_RESPONSE); }
    public boolean responseStreamed() {
        Object v = data().get(RESPONSE_STREAMED);
        return v instanceof Boolean b && b;
    }
    public String sessionToken() { return (String) data().get(SESSION_TOKEN); }
    public String sessionUserName() { return (String) data().get(SESSION_USER_NAME); }

    public int iteration() {
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
