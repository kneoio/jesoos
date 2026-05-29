package com.semantyca.jesoos.service.agenda;

import com.semantyca.mixpla.model.cnst.MixingType;

public class MixingTypeShuffler {

    public static MixingStrategy selectStrategy(int availableSongCount, boolean allowIntros, double talkativity) {
        return selectStrategy(availableSongCount, allowIntros, talkativity, null, 0, 0);
    }

    public static MixingStrategy selectStrategy(int availableSongCount, boolean allowIntros, double talkativity,
                                                 MixingType lastType, int consecutiveCount) {
        return selectStrategy(availableSongCount, allowIntros, talkativity, lastType, consecutiveCount, 0);
    }

    public static MixingStrategy selectStrategy(int availableSongCount, boolean allowIntros, double talkativity,
                                                 MixingType lastType, int consecutiveCount, int consecutive2SongCount) {
        return selectStrategy(availableSongCount, allowIntros, talkativity, lastType, consecutiveCount, consecutive2SongCount, 0);
    }

    public static MixingStrategy selectStrategy(int availableSongCount, boolean allowIntros, double talkativity,
                                                 MixingType lastType, int consecutiveCount, int consecutive2SongCount,
                                                 int consecutiveIntroCount) {
        if (consecutiveIntroCount >= 2) {
            allowIntros = false;
        }
        if (allowIntros && Math.random() < talkativity) {
            if (availableSongCount == 1) {
                MixingType type = Math.random() < 0.5 ? MixingType.INTRO_SONG : MixingType.JINGLE_INTRO_SONG;
                return new MixingStrategy(type, 1, true);
            }
            // Force single after 2 consecutive 2-song entries, otherwise ~50% chance of 2-song
            if (consecutive2SongCount < 2 && Math.random() < 0.5) {
                MixingType type = Math.random() < 0.5 ? MixingType.INTRO_SONG_INTRO_SONG : MixingType.SONG_INTRO_SONG;
                return new MixingStrategy(type, 2, true);
            }
            return new MixingStrategy(MixingType.INTRO_SONG, 1, true);
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
        MixingType type = Math.random() < 0.5 ? MixingType.SONG_ONLY : MixingType.FILLER_JINGLE;
        return new MixingStrategy(type, 1, false);
    }

    private static boolean isTwoSong(MixingType type) {
        return type == MixingType.INTRO_SONG_INTRO_SONG
                || type == MixingType.SONG_INTRO_SONG
                || type == MixingType.SONG_CROSSFADE_SONG;
    }
}
