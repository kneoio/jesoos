package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class LiveStreamInfoTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "live_stream_info",
                "Get live stream information. Use when user asks what's playing now, what's on the schedule today, what scene is active, or what songs are coming up.",
                Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"get_live_queue"},
                                "description", "What to retrieve: 'get_live_queue' returns what is currently playing and what songs are queued next")
                ),
                List.of("action")
        );
    }
}
