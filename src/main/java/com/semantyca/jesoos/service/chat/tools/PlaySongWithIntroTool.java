package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlaySongWithIntroTool {

    public static LlmTool toTool(String djLanguages) {
        return toTool(djLanguages, false);
    }

    public static LlmTool toTool(String djLanguages, boolean allowListenerVoice) {
        String langHint = djLanguages != null && !djLanguages.isBlank()
                ? "MUST be written in: " + djLanguages + ". Never use the user's chat language."
                : "Must be written in the station's primary broadcast language, never the user's chat language.";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("brandName", Map.of(
                "type", "string",
                "description", "The radio station slug name"));
        properties.put("songId", Map.of(
                "type", "string",
                "description", "UUID of the song from search results"));
        properties.put("textToTTSIntro", Map.of(
                "type", "string",
                "description", "DJ intro speech text — " + langHint
                        + (allowListenerVoice
                        ? " Required for a typed shout-out, and for introducing a listener recording "
                        + "(e.g. 'Now John is sending you a message'). Omit when the listener recording is the whole intro."
                        : "")));
        properties.put("priority", Map.of(
                "type", "integer",
                "description", "Priority: 8=normal, 7=high"));
        List<String> required = List.of("brandName", "songId", "textToTTSIntro");
        if (allowListenerVoice) {
            properties.put("listenerAudioFilename", Map.of(
                    "type", "string",
                    "description", "Basename of the listener recording after transcribe_listener_audio succeeded. "
                            + "With textToTTSIntro → DJ announcement then listener then song. "
                            + "Without textToTTSIntro → listener recording then song."));
            required = List.of("brandName", "songId");
        }
        return LlmTool.of(
                "play_song_with_intro",
                allowListenerVoice
                        ? "Queue a song with a DJ TTS intro, a listener voice recording, or both"
                        : "Queue a song with custom DJ intro speech",
                properties,
                required
        );
    }
}
