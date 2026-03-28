package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.MergingType;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;


public class TimelineBuilder {
    private static final Logger LOGGER = Logger.getLogger(TimelineBuilder.class);
    private static final int AVG_INTRO_DURATION_SECONDS = 30;

    public List<TimelineEntry> buildTimeline(LiveScene scene,
                                             List<SongEntry> songs,
                                             double talkativity,
                                             List<ScenePrompt> introPrompts) {

        List<TimelineEntry> timeline = new ArrayList<>();

        if (songs == null || songs.isEmpty()) {
            LOGGER.warnf("Scene '%s' has no songs, timeline is empty", scene.getSceneTitle());
            return timeline;
        }

        LocalTime nowInZone = LocalTime.now(scene.getTimeZone());
        LocalDate startDate = scene.getOriginalStartTime().isAfter(nowInZone)
                ? LocalDate.now(scene.getTimeZone()).minusDays(1)
                : LocalDate.now(scene.getTimeZone());
        LocalDateTime currentTime = startDate.atTime(scene.getOriginalStartTime());
        boolean allowIntros = introPrompts != null && !introPrompts.isEmpty() &&
                             introPrompts.stream().anyMatch(ScenePrompt::isActive);

        LOGGER.infof("Building timeline for scene '%s' with talkativity=%.2f, allowIntros=%s",
                scene.getSceneTitle(), talkativity, allowIntros);

        int songIndex = 0;
        while (songIndex < songs.size()) {
            int remainingSongs = songs.size() - songIndex;
            MixingTypeShuffler.MixingStrategy strategy = MixingTypeShuffler.selectStrategy(remainingSongs, allowIntros, talkativity);
            
            List<TimelineEntry> batchEntries = new ArrayList<>();
            for (int i = 0; i < strategy.songsQuantity() && songIndex < songs.size(); i++) {
                List<SongEntry> songList;
                if (strategy.songsQuantity() == 2 && songIndex + 1 < songs.size()) {
                    songList = List.of(songs.get(songIndex), songs.get(songIndex + 1));
                    songIndex++; // Skip the next song as it's already included
                } else {
                    songList = List.of(songs.get(songIndex));
                }

                TimelineEntry entry = new TimelineEntry(
                    songIndex,
                    currentTime,
                    songList,
                    strategy.mergingType(),
                    strategy.needsIntros(),
                    strategy.mergingType().equals(MergingType.FILLER_JINGLE)
                );
                
                batchEntries.add(entry);
                timeline.add(entry);
                songIndex++;
            }
            
            int batchDuration = calculateBatchDuration(batchEntries, strategy);
            currentTime = currentTime.plusSeconds(batchDuration);
        }

        LOGGER.infof("Built timeline for scene '%s': %d entries, duration: %d seconds, allowIntros: %s",
                scene.getSceneTitle(), timeline.size(),
                calculateTotalDuration(timeline), allowIntros);

        scene.setTimelineBuild(true);
        return timeline;
    }

    private int calculateBatchDuration(List<TimelineEntry> batchEntries, MixingTypeShuffler.MixingStrategy strategy) {
        if (batchEntries.isEmpty()) {
            return 0;
        }

        int totalSongDuration = batchEntries.stream()
                .mapToInt(TimelineEntry::getEstimatedDurationSeconds)
                .sum();

        int introDuration = 0;
        if (strategy.needsIntros()) {
            introDuration = batchEntries.size() * AVG_INTRO_DURATION_SECONDS;
        }

        int jingleDuration = 0;
        if (batchEntries.stream().anyMatch(TimelineEntry::isHasJingle)) {
            jingleDuration = 10;
        }

        int crossfadeReduction = 0;
        if (strategy.mergingType() == MergingType.SONG_CROSSFADE_SONG && batchEntries.size() == 2) {
            crossfadeReduction = 10;
        }

        return totalSongDuration + introDuration + jingleDuration - crossfadeReduction;
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