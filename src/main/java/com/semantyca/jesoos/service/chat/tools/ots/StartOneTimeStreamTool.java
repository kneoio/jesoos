package com.semantyca.jesoos.service.chat.tools.ots;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class StartOneTimeStreamTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "start_one_time_stream",
                "Start a one-time radio stream on a brand station using a specific script",
                Map.of(
                        "brandSlugName", Map.of(
                                "type", "string",
                                "description", "Slug name of the base radio station brand"),
                        "scriptId", Map.of(
                                "type", "string",
                                "description", "UUID of the script (from list_ots_scripts)"),
                        "userVariables", Map.of(
                                "type", "object",
                                "description", "Key-value pairs for script required variables collected from the user"),
                        "startImmediately", Map.of(
                                "type", "boolean",
                                "description", "Whether to start the stream immediately (default true)")
                ),
                List.of("brandSlugName", "scriptId")
        );
    }
}
