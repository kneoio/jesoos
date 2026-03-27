package com.semantyca.jesoos.model.stream;

import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import lombok.Getter;

import java.util.UUID;

@Getter
public class SongEntry {
    private final UUID id;
    private final SoundFragment soundFragment;
    private final PromptEntry promptEntry;
    private final int sequenceNumber;
    private final int durationSeconds;

    public SongEntry(SoundFragment soundFragment, PromptEntry promptEntry, int sequenceNumber) {
        this.promptEntry = promptEntry;
        this.id = UUID.randomUUID();
        this.soundFragment = soundFragment;
        this.sequenceNumber = sequenceNumber;
        this.durationSeconds = soundFragment.getLength() != null 
            ? (int) soundFragment.getLength().toSeconds() 
            : 180;
    }

}
