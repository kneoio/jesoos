package com.semantyca.jesoos.service.agenda;

import com.semantyca.mixpla.model.cnst.MixingType;

public class MixingTypeShuffler {

    /** Radio: jingles are always eligible (probabilistic, see JINGLE_CHANCE below). */
    public static MixingStrategy selectStrategy(int availableSongCount, boolean allowIntros, double talkativity,
                                                 MixingType lastType, int consecutiveCount, int consecutive2SongCount,
                                                 int consecutiveIntroCount) {
        if (consecutiveIntroCount >= 2 && talkativity < 1.0) {
            allowIntros = false;
        }
        if (allowIntros && Math.random() < talkativity) {
            if (availableSongCount == 1) {
                MixingType type = Math.random() < 0.5 ? MixingType.JINGLE_INTRO_SONG : MixingType.INTRO_SONG;
                return new MixingStrategy(type, 1, true);
            }
            // Force single after 2 consecutive 2-song entries, otherwise ~50% chance of 2-song
            if (consecutive2SongCount < 2 && Math.random() < 0.5) {
                MixingType type = Math.random() < 0.5 ? MixingType.INTRO_SONG_INTRO_SONG : MixingType.SONG_INTRO_SONG;
                return new MixingStrategy(type, 2, true);
            }
            MixingType type = Math.random() < 0.5 ? MixingType.JINGLE_INTRO_SONG : MixingType.INTRO_SONG;
            return new MixingStrategy(type, 1, true);
        }

        // No-TTS path — SONG_ONLY, SONG_CROSSFADE_SONG, FILLER_JINGLE
        boolean blockCrossfade = consecutive2SongCount >= 2 || (consecutiveCount >= 2 && lastType == MixingType.SONG_CROSSFADE_SONG);
        boolean blockSingle = consecutiveCount >= 2 && (lastType == MixingType.SONG_ONLY || lastType == MixingType.FILLER_JINGLE);

        if (availableSongCount >= 2 && !blockCrossfade && Math.random() < 0.25) {
            return new MixingStrategy(MixingType.SONG_CROSSFADE_SONG, 2, false);
        }
        if (blockSingle && availableSongCount >= 2 && !blockCrossfade) {
            return new MixingStrategy(MixingType.SONG_CROSSFADE_SONG, 2, false);
        }
        MixingType type = Math.random() < 0.5 ? MixingType.FILLER_JINGLE : MixingType.SONG_ONLY;
        return new MixingStrategy(type, 1, false);
    }

    /**
     * OTS LOOP: talkativity is the same coin-flip as radio, but only OTS-supported mix types
     * ({@code INTRO_SONG}, {@code SONG_INTRO_SONG}, {@code INTRO_SONG_INTRO_SONG},
     * {@code SONG_CROSSFADE_SONG}, {@code SONG_ONLY}) — no jingles.
     */
    public static MixingStrategy selectOtsStrategy(int availableSongCount, boolean allowIntros, double talkativity,
                                                   int consecutive2SongCount, int consecutiveIntroCount) {
        if (consecutiveIntroCount >= 2 && talkativity < 1.0) {
            allowIntros = false;
        }
        if (allowIntros && Math.random() < talkativity) {
            if (availableSongCount == 1) {
                return new MixingStrategy(MixingType.INTRO_SONG, 1, true);
            }
            if (consecutive2SongCount < 2 && Math.random() < 0.5) {
                MixingType type = Math.random() < 0.5 ? MixingType.INTRO_SONG_INTRO_SONG : MixingType.SONG_INTRO_SONG;
                return new MixingStrategy(type, 2, true);
            }
            return new MixingStrategy(MixingType.INTRO_SONG, 1, true);
        }
        if (availableSongCount >= 2 && consecutive2SongCount < 2 && Math.random() < 0.5) {
            return new MixingStrategy(MixingType.SONG_CROSSFADE_SONG, 2, false);
        }
        return new MixingStrategy(MixingType.SONG_ONLY, 1, false);
    }

    /**
     * The one entry of a one-time-run scene (OTS, or a radio ONE_TIME slot). Speaks only when the
     * scene has active intro content — otherwise the song plays as {@code SONG_ONLY}.
     */
    public static MixingStrategy selectOneTimeRunStrategy(boolean allowIntros) {
        return allowIntros
                ? new MixingStrategy(MixingType.INTRO_SONG, 1, true)
                : new MixingStrategy(MixingType.SONG_ONLY, 1, false);
    }
}
