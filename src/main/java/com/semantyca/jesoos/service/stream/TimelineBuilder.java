package com.semantyca.jesoos.service.stream;

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


public class TimelineBuilder {
    private static final Logger LOGGER = Logger.getLogger(TimelineBuilder.class);

    public List<TimelineEntry> buildTimeline(LiveScene scene,
                                             List<SongEntry> songs,
                                             double talkativity,
                                             List<ScenePrompt> introPrompts) {

        List<TimelineEntry> timeline = new ArrayList<>();

        if (songs == null || songs.isEmpty()) {
            LOGGER.warnf("Scene '%s' has no songs, timeline is empty", scene.getSceneTitle());
            return timeline;
        }

        LocalDateTime currentTime = LocalDate.now(scene.getTimeZone()).atTime(scene.getOriginalStartTime());
        boolean allowIntros = introPrompts != null && !introPrompts.isEmpty() &&
                             introPrompts.stream().anyMatch(ScenePrompt::isActive);

        LOGGER.infof("Building timeline for scene '%s' with talkativity=%.2f, allowIntros=%s",
                scene.getSceneTitle(), talkativity, allowIntros);

        int songIndex = 0;
        int sequenceNumber = 0;
        while (songIndex < songs.size()) {
            int remainingSongs = songs.size() - songIndex;
            MixingTypeShuffler.MixingStrategy strategy = MixingTypeShuffler.selectStrategy(remainingSongs, allowIntros, talkativity);

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

            int duration = entry.getEstimatedDurationSeconds();
            if (strategy.mergingType() == MergingType.SONG_CROSSFADE_SONG && songList.size() == 2) {
                duration -= 10;
            }
            currentTime = currentTime.plusSeconds(duration);
        }

        LOGGER.infof("Built timeline for scene '%s': %d entries, duration: %d seconds, allowIntros: %s",
                scene.getSceneTitle(), timeline.size(),
                calculateTotalDuration(timeline), allowIntros);

        scene.setTimelineBuild(true);
        return timeline;
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
