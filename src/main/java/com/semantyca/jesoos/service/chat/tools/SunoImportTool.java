package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.service.chat.llm.LlmTool;

import java.util.List;
import java.util.Map;

public class SunoImportTool {

    public static LlmTool toTool() {
        return LlmTool.of(
                "import_from_suno",
                "Import an artist's track from a Suno share link instead of a file upload. Downloads the track from Suno's CDN and returns a temp_filename. After it returns ok:true, continue exactly like a normal upload: collect title/artist/genre_names if missing, then call upload_song with the returned temp_filename. Eligibility is the same as upload_song (listener has the station artist label). No upload button / send_ui_command is needed for this path.",
                Map.of(
                        "suno_url", Map.of(
                                "type", "string",
                                "description", "The Suno link the artist shared (e.g. https://suno.com/song/<id>)")
                ),
                List.of("suno_url")
        );
    }
}
