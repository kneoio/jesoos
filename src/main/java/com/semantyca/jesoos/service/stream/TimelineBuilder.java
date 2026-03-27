package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.MergingType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class TimelineBuilder {
    private static final Logger LOGGER = Logger.getLogger(TimelineBuilder.class);
    private static final int AVG_INTRO_DURATION_SECONDS = 30;

    private final MixingTypeShuffler mixingTypeShuffler;

    @Inject
    public TimelineBuilder(MixingTypeShuffler mixingTypeShuffler) {
        this.mixingTypeShuffler = mixingTypeShuffler;
    }

    public List<TimelineEntry> buildTimeline(LiveScene scene) {
        List<TimelineEntry> timeline = new ArrayList<>();
        List<SongEntry> songs = scene.getSongs();

        if (songs.isEmpty()) {
            LOGGER.warnf("Scene '%s' has no songs, timeline is empty", scene.getSceneTitle());
            return timeline;
        }

        LocalDateTime currentTime = scene.getScheduledStartTime();
        double talkativity = scene.getTalkativity();
        boolean allowIntros = !scene.getIntroPrompts().isEmpty() &&
                             scene.getIntroPrompts().stream().anyMatch(ScenePrompt::isActive);

        int songIndex = 0;
        int batchId = 0;

        LOGGER.infof("Building timeline for scene '%s' with talkativity=%.2f, allowIntros=%s",
                scene.getSceneTitle(), talkativity, allowIntros);

        while (songIndex < songs.size()) {
            int remainingSongs = songs.size() - songIndex;
            MixingTypeShuffler.MixingStrategy strategy = mixingTypeShuffler.selectStrategy(remainingSongs, allowIntros, talkativity);
            
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
                    strategy.mergingType().equals(MergingType.FILLER_JINGLE),
                    batchId
                );
                
                batchEntries.add(entry);
                timeline.add(entry);
                songIndex++;
            }
            
            int batchDuration = calculateBatchDuration(batchEntries, strategy);
            currentTime = currentTime.plusSeconds(batchDuration);
            batchId++;
        }

        LOGGER.infof("Built timeline for scene '%s': %d entries, %d batches, duration: %d seconds, allowIntros: %s",
                scene.getSceneTitle(), timeline.size(), batchId, 
                calculateTotalDuration(timeline), allowIntros);

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
        LocalDateTime start = timeline.get(0).getScheduledEmissionTime();
        LocalDateTime end = timeline.get(timeline.size() - 1).getScheduledEmissionTime();
        return (int) java.time.Duration.between(start, end).getSeconds();
    }
}
