package com.semantyca.djinn.model.stream;

import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
public class PendingSongEntry {
    private final UUID id;
    private final SoundFragment soundFragment;
    private final LocalDateTime scheduledStartTime;
    private final int durationSeconds;

    public PendingSongEntry(SoundFragment soundFragment, LocalDateTime scheduledStartTime) {
        this.id = UUID.randomUUID();
        this.soundFragment = soundFragment;
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = soundFragment.getLength() != null 
            ? (int) soundFragment.getLength().toSeconds() 
            : 180;
    }

    public PendingSongEntry(UUID id, SoundFragment soundFragment, LocalDateTime scheduledStartTime, int durationSeconds) {
        this.id = id;
        this.soundFragment = soundFragment;
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = durationSeconds;
    }

}
