package com.semantyca.jesoos.service.agenda;

import com.semantyca.mixpla.model.cnst.MergingTypeMeta;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.mixpla.model.CustomAction;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.MixingType;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class TimelineBuilder {
    private static final Logger LOGGER = Logger.getLogger(TimelineBuilder.class);

    /**
     * If the timeline overshoots the scene budget by more than this many seconds,
     * the last entry's intro is stripped to reduce TTS load.
     */
    public static final int INTRO_TRIM_OVERSHOOT_THRESHOLD_SECONDS = 30;

    @FunctionalInterface
    private interface StrategySelector {
        MixingStrategy select(int availableSongCount, boolean allowIntros, double talkativity,
                              MixingType lastType, int consecutiveCount, int consecutive2SongCount,
                              int consecutiveIntroCount);
    }

    public List<TimelineEntry> buildTimeline(LiveScene scene,
                                             List<SongEntry> songs,
                                             int sceneDurationSeconds,
                                             double talkativity,
                                             List<ScenePrompt> introPrompts,
                                             List<CustomAction> actions) {
        return buildTimeline(scene, songs, sceneDurationSeconds, talkativity, introPrompts, actions,
                MixingTypeShuffler::selectStrategy, true);
    }

    /**
     * OTS: unlike radio, never overshoot-trims the last entry's intro. SONG_ONLY should only ever
     * come from a scene having no active intro content, not from a budget-overshoot downgrade.
     */
    public List<TimelineEntry> buildOtsTimeline(LiveScene scene,
                                                List<SongEntry> songs,
                                                int sceneDurationSeconds,
                                                double talkativity,
                                                List<ScenePrompt> introPrompts,
                                                List<CustomAction> actions) {
        return buildTimeline(scene, songs, sceneDurationSeconds, talkativity, introPrompts, actions,
                (availableSongCount, allowIntros, ignoredTalkativity, lastType, consecutiveCount, consecutive2SongCount, consecutiveIntroCount) ->
                        MixingTypeShuffler.selectOtsStrategy(availableSongCount, consecutive2SongCount),
                false);
    }

    private List<TimelineEntry> buildTimeline(LiveScene scene,
                                              List<SongEntry> songs,
                                              int sceneDurationSeconds,
                                              double talkativity,
                                              List<ScenePrompt> introPrompts,
                                              List<CustomAction> actions,
                                              StrategySelector strategySelector,
                                              boolean allowOvershootTrim) {

        List<TimelineEntry> timeline = new ArrayList<>();

        List<ScenePrompt> contentPrompts = scene.getContentPrompts();
        boolean hasGeneratedContent = contentPrompts != null && !contentPrompts.isEmpty()
                && contentPrompts.stream().anyMatch(ScenePrompt::isActive);

        if ((songs == null || songs.isEmpty()) && !hasGeneratedContent) {
            LOGGER.warnf("Scene '%s' has no songs and no generated content, timeline is empty", scene.getSceneTitle());
            return timeline;
        }

        LocalDate sceneDate = LocalDate.now(scene.getTimeZone());
        LocalDateTime currentTime = sceneDate.atTime(scene.getOriginalStartTime());
        // If the scene's start lands more than 12 h in the future the station started after midnight
        // and this is the previous night's overnight scene — shift it back one day so past entries
        // fire immediately instead of waiting ~24 h.
        LocalDateTime nowDateTime = LocalDateTime.now(scene.getTimeZone());
        if (currentTime.isAfter(nowDateTime.plusHours(12))) {
            sceneDate = sceneDate.minusDays(1);
            currentTime = sceneDate.atTime(scene.getOriginalStartTime());
        }
        boolean hasActivePrompts = introPrompts != null && !introPrompts.isEmpty()
                && introPrompts.stream().anyMatch(ScenePrompt::isActive);
        boolean hasActiveActions = actions != null && !actions.isEmpty();
        boolean allowIntros = hasActivePrompts || hasActiveActions;

        /*LOGGER.infof("Building timeline for scene '%s' with talkativity=%.2f, allowIntros=%s",
                scene.getSceneTitle(), talkativity, allowIntros);*/

        int songIndex = 0;
        int sequenceNumber = 0;

        if (hasGeneratedContent) {
            TimelineEntry generatedEntry = new TimelineEntry(
                    sequenceNumber,
                    currentTime,
                    List.of(),
                    MixingType.JINGLE_GENERATED_JINGLE_WITH_BACKGROUND,
                    false,
                    true
            );
            generatedEntry.setGenerated(true);
            generatedEntry.setEstimatedDurationSeconds(MergingTypeMeta.AVERAGE_GENERATED_CONTENT_DURATION_SECONDS);
            timeline.add(generatedEntry);
            sequenceNumber++;
            currentTime = currentTime.plusSeconds(MergingTypeMeta.AVERAGE_GENERATED_CONTENT_DURATION_SECONDS);
            LOGGER.infof("Scene '%s': inserted generated content slot (%ds) at position 0",
                    scene.getSceneTitle(), MergingTypeMeta.AVERAGE_GENERATED_CONTENT_DURATION_SECONDS);
        }
        MixingType lastMixingType = null;
        int consecutiveMixingCount = 0;
        int consecutive2SongCount = 0;
        int consecutiveIntroCount = 0;
        while (true) {
            assert songs != null;
            if (!(songIndex < songs.size())) break;
            int remainingSongs = songs.size() - songIndex;
            MixingStrategy strategy = strategySelector.select(remainingSongs, allowIntros, talkativity, lastMixingType, consecutiveMixingCount, consecutive2SongCount, consecutiveIntroCount);

            List<SongEntry> songList;
            if (strategy.songsQuantity() == 2 && songIndex + 1 < songs.size()) {
                songList = List.of(songs.get(songIndex), songs.get(songIndex + 1));
                songIndex += 2;
            } else {
                songList = List.of(songs.get(songIndex));
                songIndex++;
            }

            MixingType mergingType = strategy.mergingType();
            boolean hasJingle = mergingType == MixingType.FILLER_JINGLE || mergingType == MixingType.JINGLE_INTRO_SONG;

            TimelineEntry entry = new TimelineEntry(
                sequenceNumber,
                currentTime,
                songList,
                mergingType,
                strategy.needsIntros(),
                hasJingle
            );

            timeline.add(entry);
            sequenceNumber++;

            if (strategy.mergingType() == lastMixingType) {
                consecutiveMixingCount++;
            } else {
                lastMixingType = strategy.mergingType();
                consecutiveMixingCount = 1;
            }
            consecutive2SongCount = songList.size() == 2 ? consecutive2SongCount + 1 : 0;
            consecutiveIntroCount = strategy.needsIntros() ? consecutiveIntroCount + 1 : 0;

            int stride = entry.getEstimatedDurationSeconds()
                    - MergingTypeMeta.of(strategy.mergingType()).crossfadeOverlapSeconds();
            currentTime = currentTime.plusSeconds(stride);
        }

        int contentDurationSeconds = calculateContentDurationSeconds(timeline);
        int fitSeconds = sceneDurationSeconds - contentDurationSeconds;

        if (allowOvershootTrim && -fitSeconds > INTRO_TRIM_OVERSHOOT_THRESHOLD_SECONDS && !timeline.isEmpty()) {
            TimelineEntry last = timeline.getLast();
            MixingType downgraded = INTRO_DOWNGRADE.get(last.getMixingStrategy());
            if (downgraded != null) {
                int savedSeconds = MergingTypeMeta.of(last.getMixingStrategy()).audioOverheadSeconds()
                        - MergingTypeMeta.of(downgraded).audioOverheadSeconds();
                last.setMixingStrategy(downgraded);
                last.setHasIntro(false);
                last.setEstimatedDurationSeconds(last.getEstimatedDurationSeconds() - savedSeconds);
                fitSeconds += savedSeconds;
                LOGGER.infof("Scene '%s': overshoot %ds exceeded threshold %ds — stripped intro from last entry (saved %ds, new fit %ds)",
                        scene.getSceneTitle(), -fitSeconds + savedSeconds,
                        INTRO_TRIM_OVERSHOOT_THRESHOLD_SECONDS, savedSeconds, fitSeconds);
            }
        }

        if (fitSeconds > 0) {
            LOGGER.warnf("Scene '%s': gap of %ds at end of window — not enough content",
                    scene.getSceneTitle(), fitSeconds);
        }

        scene.setFitSeconds(fitSeconds);

        /*LOGGER.infof("Built timeline for scene '%s': %d entries, content %ds, budget %ds, fit %ds, allowIntros: %s",
                scene.getSceneTitle(), timeline.size(),
                contentDurationSeconds, sceneDurationSeconds, fitSeconds, allowIntros);*/

        scene.setTimelineBuild(true);
        return timeline;
    }

    private int calculateContentDurationSeconds(List<TimelineEntry> timeline) {
        if (timeline.isEmpty()) return 0;
        TimelineEntry last = timeline.getLast();
        LocalDateTime contentEnd = last.getScheduledEmissionTime()
                .plusSeconds(last.getEstimatedDurationSeconds());
        return (int) java.time.Duration.between(
                timeline.getFirst().getScheduledEmissionTime(), contentEnd).getSeconds();
    }

    private static final Map<MixingType, MixingType> INTRO_DOWNGRADE = Map.of(
            MixingType.INTRO_SONG,             MixingType.SONG_ONLY,
            MixingType.LISTENER_INTRO_SONG,    MixingType.SONG_ONLY,
            MixingType.JINGLE_INTRO_SONG,      MixingType.FILLER_JINGLE,
            MixingType.INTRO_SONG_INTRO_SONG,  MixingType.SONG_CROSSFADE_SONG,
            MixingType.SONG_INTRO_SONG,        MixingType.SONG_CROSSFADE_SONG
    );

}
