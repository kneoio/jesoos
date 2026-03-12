package com.semantyca.jesoos.service.stream;

import com.semantyca.mixpla.model.cnst.MergingType;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MixingTypeStrategy {

    public MixingTypeConfig selectStrategy(int availableSongCount, boolean hasIntros) {
        if (availableSongCount == 0) {
            throw new IllegalArgumentException("Cannot select merging type for 0 songs");
        }

        if (!hasIntros) {
            MergingType type = availableSongCount >= 2 ? MergingType.SONG_CROSSFADE_SONG : MergingType.SONG_ONLY;
            int batchSize = availableSongCount >= 2 ? 2 : 1;
            return new MixingTypeConfig(type, batchSize, false);
        }

        if (availableSongCount == 1) {
            MergingType type = Math.random() < 0.5 ? MergingType.INTRO_SONG : MergingType.SONG_ONLY;
            return new MixingTypeConfig(type, 1, needsIntros(type));
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
            return new MixingTypeConfig(type, 2, needsIntros(type));
        }
    }

    private boolean needsIntros(MergingType mergingType) {
        return mergingType != MergingType.SONG_ONLY && mergingType != MergingType.SONG_CROSSFADE_SONG;
    }

    public record MixingTypeConfig(MergingType mergingType, int batchSize, boolean needsIntros) {
    }
}
