package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class StartOneTimeStreamTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandSlugName", Map.of(
                                "type", "string",
                                "description", "Slug name of the base radio station brand"),
                        "scriptId", Map.of(
                                "type", "string",
                                "description", "UUID of the script to use for this one-time stream"),
                        "startImmediately", Map.of(
                                "type", "boolean",
                                "description", "Whether to start the stream immediately (default true)")
                )))
                .required(List.of("brandSlugName", "scriptId"))
                .build();

        return Tool.builder()
                .name("start_one_time_stream")
                .description("Start a one-time radio stream on a brand station using a specific script")
                .inputSchema(schema)
                .build();
    }
}
