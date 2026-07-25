package com.semantyca.jesoos.service.live.scripting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns spectra's raw {@code SoundFragment.addInfo} (bpm, key, moods, genres, danceability, loudness,
 * ai check ...) into a short, DJ-relevant vibe phrase for the intro draft — dropping the internal
 * signal (key strength, beats confidence, raw loudness, genre scores, ai caveat) that means nothing
 * on air.
 *
 * <p>{@code addInfo} is optional: it is only populated for tracks spectra has analysed, and individual
 * fields may come and go as spectra evolves. Any absent map or field is skipped, so an unanalyzed
 * track simply yields {@code ""} and contributes no vibe line.
 */
public final class AddInfoInterpreter {

    private static final double MOOD_THRESHOLD = 0.5;
    private static final double DANCEABLE_THRESHOLD = 0.6;
    private static final double SECOND_GENRE_RATIO = 0.7;

    private AddInfoInterpreter() {
    }

    public static String interpret(Map<String, Object> addInfo) {
        if (addInfo == null || addInfo.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();

        Double bpm = toDouble(addInfo.get("bpm"));
        if (bpm != null) {
            parts.add(tempoBand(bpm) + " (" + Math.round(bpm) + " BPM)");
        }

        if (addInfo.get("scale") instanceof String scale && !scale.isBlank()) {
            parts.add(scale.trim() + " key");
        }

        String moods = dominantMoods(addInfo.get("moods"));
        if (!moods.isEmpty()) {
            parts.add(moods);
        }

        Double danceability = toDouble(addInfo.get("danceability"));
        if (danceability != null && danceability >= DANCEABLE_THRESHOLD) {
            parts.add("danceable");
        }

        String genre = topGenre(addInfo.get("top_genres"));
        if (!genre.isEmpty()) {
            parts.add(genre);
        }

        if (isAiGenerated(addInfo.get("ai_generated_metadata_check"))) {
            parts.add("AI-generated");
        }

        return String.join(", ", parts);
    }

    private static String tempoBand(double bpm) {
        if (bpm < 70) return "slow";
        if (bpm < 100) return "mid-tempo";
        if (bpm < 130) return "upbeat";
        return "fast";
    }

    /** Only the moods actually present in the track (score above threshold), strongest first. */
    private static String dominantMoods(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return "";
        }
        record Mood(String name, double score) {
        }
        List<Mood> present = new ArrayList<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Double score = toDouble(e.getValue());
            if (score != null && score >= MOOD_THRESHOLD && e.getKey() != null) {
                present.add(new Mood(e.getKey().toString(), score));
            }
        }
        present.sort((a, b) -> Double.compare(b.score(), a.score()));
        return present.stream().map(Mood::name).collect(Collectors.joining("/"));
    }

    /** Top detected genre (label prefix stripped); a second only when it's nearly as strong. */
    private static String topGenre(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        String first = genreName(list.get(0));
        if (first.isEmpty()) {
            return "";
        }
        Double firstScore = genreScore(list.get(0));
        if (list.size() > 1 && firstScore != null) {
            String second = genreName(list.get(1));
            Double secondScore = genreScore(list.get(1));
            if (!second.isEmpty() && secondScore != null && secondScore >= SECOND_GENRE_RATIO * firstScore) {
                return first + "/" + second;
            }
        }
        return first;
    }

    private static String genreName(Object entry) {
        if (entry instanceof Map<?, ?> m && m.get("genre") != null) {
            String g = m.get("genre").toString();
            int sep = g.lastIndexOf("---");
            return (sep >= 0 ? g.substring(sep + 3) : g).trim();
        }
        return "";
    }

    private static Double genreScore(Object entry) {
        return entry instanceof Map<?, ?> m ? toDouble(m.get("score")) : null;
    }

    private static boolean isAiGenerated(Object raw) {
        return raw instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("suspected_ai_generated"));
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
