package com.semantyca.jesoos.service.stream;

import com.semantyca.mixpla.model.cnst.MergingType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Random;

@ApplicationScoped
public class MixingTypeShuffler {

    public record MixingStrategy(MergingType mergingType, int songsQuantity, boolean needsIntros) {
    }

    public MixingStrategy selectStrategy(int availableSongCount, boolean allowIntros, double talkativity) {
        boolean shouldPlayJingle = shouldPlayJingle(talkativity);

        if (shouldPlayJingle) {
            return new MixingStrategy(MergingType.FILLER_JINGLE, 1, false);
        }

        if (!allowIntros) {
            MergingType type = availableSongCount >= 2 ? MergingType.SONG_CROSSFADE_SONG : MergingType.SONG_ONLY;
            int batchSize = availableSongCount >= 2 ? 2 : 1;
            return new MixingStrategy(type, batchSize, false);
        }

        if (availableSongCount == 1) {
            MergingType type = Math.random() < 0.5 ? MergingType.INTRO_SONG : MergingType.SONG_ONLY;
            return new MixingStrategy(type, 1, needsIntros(type));
        } else {
            double rand = Math.random();
            MergingType type;
            if (rand < 0.33) {
                type = MergingType.INTRO_SONG_INTRO_SONG;
            } else if (rand < 0.66) {
                type = MergingType.SONG_CROSSFADE_SONG;
            } else {
                type = MergingType.SONG_INTRO_SONG;
            }
            return new MixingStrategy(type, 2, needsIntros(type));
        }
    }

    private static boolean shouldPlayJingle(double talkativity) {
        double jingleProbability = 1.0 - talkativity;
        double randomValue = new Random().nextDouble();
        return randomValue < jingleProbability;
    }

    private boolean needsIntros(MergingType mergingType) {
        return mergingType != MergingType.SONG_ONLY && mergingType != MergingType.SONG_CROSSFADE_SONG;
    }
}
