package com.semantyca.jesoos.model.stream;

import com.semantyca.jesoos.model.cnst.BoostType;
import com.semantyca.mixpla.model.cnst.MergingTypeMeta;
import com.semantyca.mixpla.model.cnst.MixingType;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private MixingType mixingStrategy;
    @Setter
    private boolean hasIntro;
    @Setter
    private boolean hasJingle;
    @Setter
    private boolean generated;
    @Setter
    private boolean boosted;
    @Setter
    private BoostType boostType;
    @Setter
    private int estimatedDurationSeconds;

    private final AtomicReference<TimelineEntryStatus> currentStatus = new AtomicReference<>(TimelineEntryStatus.PENDING);
    private final CopyOnWriteArrayList<StatusRecord> statusHistory = new CopyOnWriteArrayList<>();

    public record StatusRecord(TimelineEntryStatus status, Instant at) {}

    public TimelineEntry(int sequenceNumber, LocalDateTime scheduledEmissionTime,
                         List<SongEntry> songs, MixingType mixingStrategy,
                         boolean hasIntro, boolean hasJingle) {
        this.id = UUID.randomUUID();
        this.sequenceNumber = sequenceNumber;
        this.scheduledEmissionTime = scheduledEmissionTime;
        this.songs = songs;
        this.mixingStrategy = mixingStrategy;
        this.hasIntro = hasIntro;
        this.hasJingle = hasJingle;
        this.estimatedDurationSeconds = calculateEstimatedDuration(songs);
        statusHistory.add(new StatusRecord(TimelineEntryStatus.PENDING, Instant.now()));
    }

    public boolean compareAndSetStatus(TimelineEntryStatus expected, TimelineEntryStatus update) {
        if (currentStatus.compareAndSet(expected, update)) {
            statusHistory.add(new StatusRecord(update, Instant.now()));
            return true;
        }
        return false;
    }

    public TimelineEntryStatus getStatus() {
        return currentStatus.get();
    }

    public void setStatus(TimelineEntryStatus s) {
        currentStatus.set(s);
        statusHistory.add(new StatusRecord(s, Instant.now()));
    }

    public List<StatusRecord> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    private int calculateEstimatedDuration(List<SongEntry> songs) {
        int totalDuration = songs.stream()
                .mapToInt(SongEntry::getDurationSeconds)
                .sum();
        return totalDuration + MergingTypeMeta.of(this.mixingStrategy).audioOverheadSeconds();
    }
}
