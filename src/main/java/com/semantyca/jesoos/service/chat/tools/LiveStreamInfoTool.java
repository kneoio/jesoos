package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class LiveStreamInfoTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"get_agenda"},
                                "description", "What to retrieve: 'get_agenda' returns today's scene schedule and the currently active scene with upcoming songs")
                )))
                .required(List.of("action"))
                .build();

        return Tool.builder()
                .name("live_stream_info")
                .description("Get live stream information. Use when user asks what's playing now, what's on the schedule today, what scene is active, or what songs are coming up.")
                .inputSchema(schema)
                .build();
    }
}
