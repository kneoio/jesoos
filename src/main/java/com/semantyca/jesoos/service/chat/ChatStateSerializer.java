package com.semantyca.jesoos.service.chat;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import org.bsc.langgraph4j.serializer.StateSerializer;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatStateSerializer extends StateSerializer<ChatState> {

    private final ObjectMapper mapper;
    private final CollectionType historyType;

    public ChatStateSerializer() {
        super(ChatState::new);
        mapper = new ObjectMapper();
        mapper.setVisibility(
                mapper.getSerializationConfig().getDefaultVisibilityChecker()
                        .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                        .withCreatorVisibility(JsonAutoDetect.Visibility.ANY)
        );
        historyType = mapper.getTypeFactory().constructCollectionType(List.class, LlmMessage.class);
    }

    @Override
    public void writeData(Map<String, Object> data, ObjectOutput out) throws IOException {
        out.writeLong(toLong(data.get(ChatState.USER_ID)));
        writeString(out, (String) data.get(ChatState.CONNECTION_ID));
        writeString(out, (String) data.get(ChatState.BRAND_NAME));
        writeString(out, (String) data.get(ChatState.SYSTEM_PROMPT));
        writeString(out, (String) data.get(ChatState.DJ_NAME));
        writeString(out, (String) data.get(ChatState.DJ_LANGUAGES));
        writeString(out, (String) data.get(ChatState.BOT_RESPONSE));
        writeString(out, (String) data.get(ChatState.CONTEXT_BLOCK));
        out.writeInt(toInt(data.get(ChatState.ITERATION)));

        @SuppressWarnings("unchecked")
        List<LlmMessage> history = (List<LlmMessage>) data.get(ChatState.HISTORY);
        String historyJson = mapper.writeValueAsString(history != null ? history : List.of());
        writeString(out, historyJson);

        LlmToolCall toolCall = (LlmToolCall) data.get(ChatState.TOOL_CALL);
        writeString(out, toolCall != null ? mapper.writeValueAsString(toolCall) : null);
    }

    @Override
    public Map<String, Object> readData(ObjectInput in) throws IOException, ClassNotFoundException {
        Map<String, Object> data = new HashMap<>();
        data.put(ChatState.USER_ID, in.readLong());
        data.put(ChatState.CONNECTION_ID, readString(in));
        data.put(ChatState.BRAND_NAME, readString(in));
        data.put(ChatState.SYSTEM_PROMPT, readString(in));
        data.put(ChatState.DJ_NAME, readString(in));
        data.put(ChatState.DJ_LANGUAGES, readString(in));
        data.put(ChatState.BOT_RESPONSE, readString(in));
        data.put(ChatState.CONTEXT_BLOCK, readString(in));
        data.put(ChatState.ITERATION, in.readInt());

        String historyJson = readString(in);
        List<LlmMessage> history = mapper.readValue(historyJson != null ? historyJson : "[]", historyType);
        data.put(ChatState.HISTORY, new ArrayList<>(history));

        String toolCallJson = readString(in);
        if (toolCallJson != null) {
            data.put(ChatState.TOOL_CALL, mapper.readValue(toolCallJson, LlmToolCall.class));
        }

        return data;
    }

    private static void writeString(ObjectOutput out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static String readString(ObjectInput in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static long toLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        return 0L;
    }

    private static int toInt(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        return 0;
    }
}
