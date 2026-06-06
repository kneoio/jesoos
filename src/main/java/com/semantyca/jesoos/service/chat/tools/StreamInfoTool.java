package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class StreamInfoTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "stream_info",
                "Get stream information. Use 'get_current_scene' when the user asks what's playing right now, what scene is active, or what's coming up next. Use 'get_today_agenda' when the user asks about today's full schedule or planned playlist. In both cases, note that times are approximate due to stream buffering, and the system may skip tracks to catch up.",
                Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"get_current_scene", "get_today_agenda"},
                                "description", "What to retrieve: 'get_current_scene' returns the active scene and currently queued tracks (real-time, may drift due to buffering); 'get_today_agenda' returns all scenes and planned playlists for today, valid until 6 AM when the system rebuilds the schedule")
                ),
                List.of("action")
        );
    }
}
