package com.semantyca.jesoos.service.chat.tools.ots;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class ListOtsScriptsTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of()))
                .build();

        return Tool.builder()
                .name("list_ots_scripts")
                .description("List available scripts for one-time streams. Returns script id, name, and required variables the user must provide before starting.")
                .inputSchema(schema)
                .build();
    }
}
