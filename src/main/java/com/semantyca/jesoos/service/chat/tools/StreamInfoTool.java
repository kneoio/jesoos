package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class StreamInfoTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "stream_info",
                "Get stream information. Use 'get_current_scene' when the user asks what's playing right now, what scene is active, or what tracks are queued up next. Note that times are approximate due to stream buffering, and the DJ may skip tracks to catch up.",
                Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"get_current_scene"},
                                "description", "Returns the active scene and the currently queued tracks ready for broadcast")
                ),
                List.of("action")
        );
    }
}
