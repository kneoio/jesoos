package com.semantyca.jesoos.service.chat;

/**
 * Metadata scraped from a Suno song page during {@link SunoImportService#downloadToTemp}.
 * Every field except {@code songId} is best-effort: if the page parse fails the track is still
 * downloaded and {@link #empty(String)} carries only the id, so import never fails on metadata alone.
 * {@code genreTags} is Suno's free-form tag string (e.g. "jungle, drum and bass") — it is a hint for
 * the LLM to map onto the station's controlled genres, not a ready-to-use {@code genre_names} value.
 */
public record SunoTrackMetadata(
        String songId,
        String tempFilename,
        String title,
        String artist,
        String handle,
        String genreTags,
        String imageUrl,
        Double durationSeconds,
        String prompt) {

    public static SunoTrackMetadata empty(String songId) {
        return new SunoTrackMetadata(songId, null, null, null, null, null, null, null, null);
    }

    public SunoTrackMetadata withTempFilename(String tempFilename) {
        return new SunoTrackMetadata(songId, tempFilename, title, artist, handle, genreTags, imageUrl, durationSeconds, prompt);
    }
}
