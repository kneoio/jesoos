package com.semantyca.jesoos.model.cnst;

import com.semantyca.mixpla.model.cnst.MixingType;

import java.util.Map;

/**
 * Static metadata companion for {@link com.semantyca.mixpla.model.cnst.MixingType}.
 *
 * <p>Centralises timing constants so that duration estimates in
 * {@code TimelineEntry}, {@code TimelineBuilder}, and song-selection
 * budgeting in {@code AgendaService} all derive from the same numbers.
 */
public final class MergingTypeMeta {

    public static final int AVERAGE_INTRO_DURATION_SECONDS = 10;
    public static final int AVERAGE_JINGLE_DURATION_SECONDS = 5;
    public static final int CROSSFADE_OVERLAP_SECONDS = 0;
    public static final int AVERAGE_GENERATED_CONTENT_DURATION_SECONDS = 180;

    /**
     * Per-type descriptor.
     *
     * @param songsPerEntry    how many songs the strategy consumes (1 or 2)
     * @param introCount       number of TTS intros generated (0, 1, or 2)
     * @param hasJingle        whether a jingle is appended to the entry
     * @param hasCrossfade     whether the entry overlaps the next by
     *                         {@link #CROSSFADE_OVERLAP_SECONDS}
     */
    public record Info(int songsPerEntry, int introCount, boolean hasJingle, boolean hasCrossfade) {

        /**
         * Total audio overhead beyond the raw song durations.
         * Does NOT subtract the crossfade overlap — {@link #crossfadeOverlapSeconds()}
         * is kept separate because it affects scheduling stride, not audio length.
         */
        public int audioOverheadSeconds() {
            int overhead = introCount * AVERAGE_INTRO_DURATION_SECONDS;
            if (hasJingle) overhead += AVERAGE_JINGLE_DURATION_SECONDS;
            return overhead;
        }

        /** Seconds by which this entry's scheduling stride is shortened via crossfade. */
        public int crossfadeOverlapSeconds() {
            return hasCrossfade ? CROSSFADE_OVERLAP_SECONDS : 0;
        }
    }

    private static final Map<MixingType, Info> META = Map.ofEntries(
            Map.entry(MixingType.INTRO_SONG,                               new Info(1, 1, false, false)),
            Map.entry(MixingType.LISTENER_INTRO_SONG,                      new Info(1, 1, false, false)),
            Map.entry(MixingType.NOT_MIXED,                                new Info(1, 0, false, false)),
            Map.entry(MixingType.SONG_ONLY,                                new Info(1, 0, false, false)),
            Map.entry(MixingType.SONG_INTRO_SONG,                          new Info(2, 1, false, false)),
            Map.entry(MixingType.FILLER_JINGLE,                            new Info(1, 0, true,  false)),
            Map.entry(MixingType.JINGLE_INTRO_SONG,                        new Info(1, 1, true,  false)),
            Map.entry(MixingType.INTRO_SONG_INTRO_SONG,                    new Info(2, 2, false, false)),
            Map.entry(MixingType.SONG_CROSSFADE_SONG,                      new Info(2, 0, false, true)),
            Map.entry(MixingType.JINGLE_GENERATED_JINGLE_WITH_BACKGROUND,  new Info(1, 0, true, false))
    );

    public static Info of(MixingType type) {
        return META.getOrDefault(type, new Info(1, 0, false, false));
    }

    /**
     * Expected average overhead per song (seconds) as a continuous function of {@code talkativity}.
     *
     * <p>Talkativity is the probability [0,1] that any given entry takes the TTS intro path.
     * Expected overhead = talkativity × (intro-path avg) + (1−talkativity) × (no-intro-path avg).
     *
     * <p><b>Intro path</b> — from {@code MixingTypeShuffler}: 50% INTRO_SONG_INTRO_SONG, 50% SONG_INTRO_SONG:
     * <ul>
     *   <li>INTRO_SONG_INTRO_SONG: +30 s / 2 songs = 15 s/song</li>
     *   <li>SONG_INTRO_SONG:       +15 s / 2 songs = 7.5 s/song</li>
     *   <li>→ weighted avg = <b>11.25 s/song</b></li>
     * </ul>
     *
     * <p><b>No-intro path</b> — 35% SONG_CROSSFADE_SONG, 32.5% SONG_ONLY, 32.5% FILLER_JINGLE
     * (crossfade probability is 0.35 in {@code MixingTypeShuffler}):
     * <ul>
     *   <li>SONG_CROSSFADE_SONG: −10 s / 2 songs = −5 s/song  (weight 0.35)</li>
     *   <li>SONG_ONLY:             0 s / 1 song  =  0 s/song  (weight 0.325)</li>
     *   <li>FILLER_JINGLE:        +10 s / 1 song = 10 s/song  (weight 0.325)</li>
     *   <li>→ weighted avg = 0.35×(−5) + 0.325×0 + 0.325×10 = <b>+1.5 s/song</b></li>
     * </ul>
     *
     * <p>Result: {@code 1.5 + talkativity × 9.75}
     *
     * @param talkativity 0.0–1.0 probability of taking the TTS intro path per entry
     * @return expected extra seconds per song due to mixing overhead
     */
    public static double averageOverheadPerSong(double talkativity) {
        return 1.5 + talkativity * 9.75;
    }

    private MergingTypeMeta() {}
}
