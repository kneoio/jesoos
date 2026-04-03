package com.semantyca.jesoos.service.agenda;

import com.semantyca.jesoos.model.cnst.MergingTypeMeta;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.MergingType;
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

    private static final Map<MergingType, MergingType> INTRO_DOWNGRADE = Map.of(
            MergingType.INTRO_SONG,             MergingType.SONG_ONLY,
            MergingType.LISTENER_INTRO_SONG,    MergingType.SONG_ONLY,
            MergingType.INTRO_SONG_INTRO_SONG,  MergingType.SONG_CROSSFADE_SONG,
            MergingType.SONG_INTRO_SONG,        MergingType.SONG_CROSSFADE_SONG
    );

    public List<TimelineEntry> buildTimeline(LiveScene scene,
                                             List<SongEntry> songs,
                                             int sceneDurationSeconds,
                                             double talkativity,
                                             List<ScenePrompt> introPrompts) {

        List<TimelineEntry> timeline = new ArrayList<>();

        if (songs == null || songs.isEmpty()) {
            LOGGER.warnf("Scene '%s' has no songs, timeline is empty", scene.getSceneTitle());
            return timeline;
        }

        LocalTime nowTime = LocalTime.now(scene.getTimeZone());
        LocalDate sceneDate = LocalDate.now(scene.getTimeZone());
        if (scene.getOriginalStartTime().isAfter(nowTime)) {
            sceneDate = sceneDate.minusDays(1);
        }
        LocalDateTime currentTime = sceneDate.atTime(scene.getOriginalStartTime());
        boolean allowIntros = introPrompts != null && !introPrompts.isEmpty() &&
                             introPrompts.stream().anyMatch(ScenePrompt::isActive);

        LOGGER.infof("Building timeline for scene '%s' with talkativity=%.2f, allowIntros=%s",
                scene.getSceneTitle(), talkativity, allowIntros);

        int songIndex = 0;
        int sequenceNumber = 0;
        while (songIndex < songs.size()) {
            int remainingSongs = songs.size() - songIndex;
            MixingStrategy strategy = MixingTypeShuffler.selectStrategy(remainingSongs, allowIntros, talkativity);

            List<SongEntry> songList;
            if (strategy.songsQuantity() == 2 && songIndex + 1 < songs.size()) {
                songList = List.of(songs.get(songIndex), songs.get(songIndex + 1));
                songIndex += 2;
            } else {
                songList = List.of(songs.get(songIndex));
                songIndex++;
            }

            TimelineEntry entry = new TimelineEntry(
                sequenceNumber,
                currentTime,
                songList,
                strategy.mergingType(),
                strategy.needsIntros(),
                strategy.mergingType().equals(MergingType.FILLER_JINGLE)
            );

            timeline.add(entry);
            sequenceNumber++;

            int stride = entry.getEstimatedDurationSeconds()
                    - MergingTypeMeta.of(strategy.mergingType()).crossfadeOverlapSeconds();
            currentTime = currentTime.plusSeconds(stride);
        }

        int contentDurationSeconds = calculateContentDurationSeconds(timeline);
        int fitSeconds = sceneDurationSeconds - contentDurationSeconds;

        if (-fitSeconds > INTRO_TRIM_OVERSHOOT_THRESHOLD_SECONDS && !timeline.isEmpty()) {
            TimelineEntry last = timeline.getLast();
            MergingType downgraded = INTRO_DOWNGRADE.get(last.getMixingStrategy());
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

        LOGGER.infof("Built timeline for scene '%s': %d entries, content %ds, budget %ds, fit %ds, allowIntros: %s",
                scene.getSceneTitle(), timeline.size(),
                contentDurationSeconds, sceneDurationSeconds, fitSeconds, allowIntros);

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

    private int calculateTotalDuration(List<TimelineEntry> timeline) {
        if (timeline.isEmpty()) {
            return 0;
        }
        LocalDateTime start = timeline.getFirst().getScheduledEmissionTime();
        LocalDateTime end = timeline.getLast().getScheduledEmissionTime();
        return (int) java.time.Duration.between(start, end).getSeconds();
    }
}
