package com.semantyca.jesoos.model.stream;

import com.semantyca.jesoos.service.stream.StaggeredSongScheduler;
import com.semantyca.mixpla.model.cnst.MergingType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Getter
public class TimelineEntry {
    @Setter
    private UUID id;
    @Setter
    private int sequenceNumber;
    @Setter
    private LocalDateTime scheduledEmissionTime;
    @Setter
    private List<SongEntry> songs;
    @Setter
    private MergingType mixingStrategy;
    @Setter
    private boolean hasIntro;
    @Setter
    private boolean hasJingle;
    @Setter
    private int estimatedDurationSeconds;
    private final AtomicReference<TimelineEntryStatus> status = new AtomicReference<>(TimelineEntryStatus.PENDING);

    public TimelineEntry(int sequenceNumber, LocalDateTime scheduledEmissionTime,
                         List<SongEntry> songs, MergingType mixingStrategy,
                         boolean hasIntro, boolean hasJingle) {
        this.id = UUID.randomUUID();
        this.sequenceNumber = sequenceNumber;
        this.scheduledEmissionTime = scheduledEmissionTime;
        this.songs = songs;
        this.mixingStrategy = mixingStrategy;
        this.hasIntro = hasIntro;
        this.hasJingle = hasJingle;
        this.estimatedDurationSeconds = calculateEstimatedDuration(songs, hasIntro, hasJingle);
    }

    public boolean compareAndSetStatus(TimelineEntryStatus expected, TimelineEntryStatus update) {
        return status.compareAndSet(expected, update);
    }

    public TimelineEntryStatus getStatus() {
        return status.get();
    }
    public void setStatus(TimelineEntryStatus s) {
        status.set(s);
    }

    private int calculateEstimatedDuration(List<SongEntry> songs, boolean hasIntro, boolean hasJingle) {
        int totalDuration = songs.stream()
                .mapToInt(SongEntry::getDurationSeconds)
                .sum();
        if (hasIntro) totalDuration += 15;
        if (hasJingle) totalDuration += StaggeredSongScheduler.DEFAULT_JINGLE_DURATION;
        return totalDuration;
    }
}