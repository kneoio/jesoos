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


public class TimelineBuilder {
    private static final Logger LOGGER = Logger.getLogger(TimelineBuilder.class);

    public List<TimelineEntry> buildTimeline(LiveScene scene,
                                             List<SongEntry> songs,
                                             double talkativity,
                                             List<ScenePrompt> introPrompts,
                                             int targetDurationSeconds) {

        List<TimelineEntry> timeline = new ArrayList<>();

        if (songs == null || songs.isEmpty()) {
            LOGGER.warnf("Scene '%s' has no songs, timeline is empty", scene.getSceneTitle());
            return timeline;
        }

        LocalDateTime currentTime = LocalDate.now(scene.getTimeZone()).atTime(scene.getOriginalStartTime());
        LocalDateTime targetEndTime = currentTime.plusSeconds(targetDurationSeconds);
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

        // Change 2: ensure last entry uses a single-song strategy
        if (!timeline.isEmpty() && timeline.getLast().getSongs().size() > 1) {
            TimelineEntry last = timeline.remove(timeline.size() - 1);
            LocalDateTime entryTime = last.getScheduledEmissionTime();
            for (SongEntry song : last.getSongs()) {
                TimelineEntry single = new TimelineEntry(sequenceNumber, entryTime,
                        List.of(song), MergingType.SONG_ONLY, false, false);
                timeline.add(single);
                sequenceNumber++;
                entryTime = entryTime.plusSeconds(single.getEstimatedDurationSeconds());
            }
            currentTime = entryTime;
        }

        // Change 1: if timeline ends before next scene start, add a SONG_ONLY filler
        if (currentTime.isBefore(targetEndTime)) {
            SongEntry fillerSong = songs.get(songs.size() - 1);
            TimelineEntry filler = new TimelineEntry(sequenceNumber, currentTime,
                    List.of(fillerSong), MergingType.SONG_ONLY, false, false);
            timeline.add(filler);
            LOGGER.infof("Added filler SONG_ONLY entry for scene '%s' to cover %ds gap to next scene",
                    scene.getSceneTitle(),
                    (int) java.time.Duration.between(currentTime, targetEndTime).getSeconds());
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
