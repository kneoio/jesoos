package com.semantyca.jesoos.service.chat.tools.ots;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.Map;

public class ListOtsScriptsTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "list_ots_scripts",
                "List available scripts for one-time streams. Returns script id, name, and required variables the user must provide before starting.",
                Map.of()
        );
    }
}
