package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class TranscribeListenerAudioTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "transcribe_listener_audio",
                "Transcribe a listener voice recording (the temp file after show_record_button). "
                        + "Call this BEFORE play_song_with_intro when the listener recorded a greeting. "
                        + "Returns transcript, confidence and detected language. "
                        + "If ok is false or usable is false, reject the take — ask them to re-record or type it. "
                        + "NEVER call this on an artist music upload.",
                Map.of(
                        "temp_filename", Map.of(
                                "type", "string",
                                "description", "Basename from 'I uploaded a file: <filename>' after show_record_button")
                ),
                List.of("temp_filename")
        );
    }
}
