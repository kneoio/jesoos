package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class RadioStationControlTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brand",
                        Map.of(
                                "type", "string",
                                "description", "The brand/slug name of the radio station to control (e.g., 'sacana', 'bratan')"),
                        "action",
                        Map.of(
                                "type", "string",
                                "enum", List.of("start", "stop"),
                                "description", "The action to perform on the station: 'start' to initialize, 'stop' to shutdown")))
                )
                .required(List.of("brand", "action"))
                .build();

        return Tool.builder()
                .name("control_station")
                .description("Start or stop a radio station by initializing or shutting it down")
                .inputSchema(schema)
                .build();
    }
}
