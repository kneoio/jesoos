package com.semantyca.jesoos.service.ask;

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

public class AskStateSerializer extends StateSerializer<AskState> {

    private final ObjectMapper mapper;
    private final CollectionType historyType;

    public AskStateSerializer() {
        super(AskState::new);
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
        out.writeLong(toLong(data.get(AskState.USER_ID)));
        writeString(out, (String) data.get(AskState.CONNECTION_ID));
        writeString(out, (String) data.get(AskState.SYSTEM_PROMPT));
        writeString(out, (String) data.get(AskState.ASSISTANT_NAME));
        writeString(out, (String) data.get(AskState.BOT_RESPONSE));
        writeString(out, (String) data.get(AskState.SESSION_TOKEN));
        writeString(out, (String) data.get(AskState.SESSION_USER_NAME));
        out.writeInt(toInt(data.get(AskState.ITERATION)));
        out.writeBoolean(data.get(AskState.RESPONSE_STREAMED) instanceof Boolean b && b);

        @SuppressWarnings("unchecked")
        List<LlmMessage> history = (List<LlmMessage>) data.get(AskState.HISTORY);
        writeString(out, mapper.writeValueAsString(history != null ? history : List.of()));

        LlmToolCall toolCall = (LlmToolCall) data.get(AskState.TOOL_CALL);
        writeString(out, toolCall != null ? mapper.writeValueAsString(toolCall) : null);
    }

    @Override
    public Map<String, Object> readData(ObjectInput in) throws IOException, ClassNotFoundException {
        Map<String, Object> data = new HashMap<>();
        data.put(AskState.USER_ID, in.readLong());
        data.put(AskState.CONNECTION_ID, readString(in));
        data.put(AskState.SYSTEM_PROMPT, readString(in));
        data.put(AskState.ASSISTANT_NAME, readString(in));
        data.put(AskState.BOT_RESPONSE, readString(in));
        data.put(AskState.SESSION_TOKEN, readString(in));
        data.put(AskState.SESSION_USER_NAME, readString(in));
        data.put(AskState.ITERATION, in.readInt());
        data.put(AskState.RESPONSE_STREAMED, in.readBoolean());

        String historyJson = readString(in);
        List<LlmMessage> history = mapper.readValue(historyJson != null ? historyJson : "[]", historyType);
        data.put(AskState.HISTORY, new ArrayList<>(history));

        String toolCallJson = readString(in);
        if (toolCallJson != null) {
            data.put(AskState.TOOL_CALL, mapper.readValue(toolCallJson, LlmToolCall.class));
        }
        return data;
    }

    private static void writeString(ObjectOutput out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private static String readString(ObjectInput in) throws IOException {
        if (!in.readBoolean()) return null;
        int length = in.readInt();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
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
