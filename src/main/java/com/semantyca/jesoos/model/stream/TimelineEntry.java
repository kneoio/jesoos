package com.semantyca.jesoos.model.stream;

import com.semantyca.jesoos.service.stream.StaggeredSongScheduler;
import com.semantyca.mixpla.model.cnst.MergingType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class TimelineEntry {
    private UUID id;
    private int sequenceNumber;
    private LocalDateTime scheduledEmissionTime;
    private List<SongEntry> songs;
    private MergingType mixingStrategy;
    private boolean hasIntro;
    private boolean hasJingle;
    private int estimatedDurationSeconds;
    private int batchId;
    private TimelineEntryStatus status;

    public TimelineEntry(int sequenceNumber, LocalDateTime scheduledEmissionTime,
                         List<SongEntry> songs, MergingType mixingStrategy,
                         boolean hasIntro, boolean hasJingle, int batchId) {
        this.id = UUID.randomUUID();
        this.sequenceNumber = sequenceNumber;
        this.scheduledEmissionTime = scheduledEmissionTime;
        this.songs = songs;
        this.mixingStrategy = mixingStrategy;
        this.hasIntro = hasIntro;
        this.hasJingle = hasJingle;
        this.estimatedDurationSeconds = calculateEstimatedDuration(songs, hasIntro, hasJingle);
        this.batchId = batchId;
        this.status = TimelineEntryStatus.PENDING;
    }

    private int calculateEstimatedDuration(List<SongEntry> songs, boolean hasIntro, boolean hasJingle) {
        int totalDuration = songs.stream()
            .mapToInt(SongEntry::getDurationSeconds)
            .sum();
        
        if (hasIntro) {
            totalDuration += 30;
        }
        if (hasJingle) {
            totalDuration += StaggeredSongScheduler.DEFAULT_JINGLE_DURATION;
        }
        
        return totalDuration;
    }
}
