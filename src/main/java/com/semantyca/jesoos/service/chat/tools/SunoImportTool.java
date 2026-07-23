package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class SunoImportTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "import_from_suno",
                "Import an artist's track from a Suno share link instead of a file upload. Downloads the track from Suno's CDN AND scrapes its metadata, returning temp_filename plus (when available) title, artist, handle, genre_tags, image_url and duration_seconds. After it returns ok:true, do NOT re-ask the artist for title/artist/genre that came back populated: map genre_tags onto the station's available genres, show the artist the resolved title/artist/genre and ask them to confirm or correct it, then call upload_song with the returned temp_filename. Only ask from scratch for fields that came back empty. Eligibility is the same as upload_song (listener has the station artist label). No upload button / send_ui_command is needed for this path.",
                Map.of(
                        "suno_url", Map.of(
                                "type", "string",
                                "description", "The Suno link the artist shared (e.g. https://suno.com/song/<id>)")
                ),
                List.of("suno_url")
        );
    }
}
