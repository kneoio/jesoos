package com.semantyca.jesoos.service.agenda;

import com.semantyca.mixpla.model.cnst.MixingType;

public class MixingTypeShuffler {

    public static MixingStrategy selectStrategy(int availableSongCount, boolean allowIntros, double talkativity) {
        if (allowIntros && Math.random() < talkativity) {
            // TTS path — cost scales with talkativity
            if (availableSongCount == 1) {
                return new MixingStrategy(MixingType.INTRO_SONG, 1, true);
            }
            MixingType type = Math.random() < 0.5 ? MixingType.INTRO_SONG_INTRO_SONG : MixingType.SONG_INTRO_SONG;
            return new MixingStrategy(type, 2, true);
        }

        // No-TTS path — SONG_ONLY, SONG_CROSSFADE_SONG, FILLER_JINGLE
        if (availableSongCount >= 2 && Math.random() < 0.5) {
            return new MixingStrategy(MixingType.SONG_CROSSFADE_SONG, 2, false);
        }
        MixingType type = Math.random() < 0.5 ? MixingType.SONG_ONLY : MixingType.FILLER_JINGLE;
        return new MixingStrategy(type, 1, false);
    }
}
