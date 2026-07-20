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
    private final String sharerName;
    private final String contributorEmail;
    private final boolean priority;

    public SongEntry(SoundFragment soundFragment, PromptEntry promptEntry, int sequenceNumber) {
        this(soundFragment, promptEntry, sequenceNumber, null);
    }

    public SongEntry(SoundFragment soundFragment, PromptEntry promptEntry, int sequenceNumber, String sharerName) {
        this(soundFragment, promptEntry, sequenceNumber, sharerName, null);
    }

    public SongEntry(SoundFragment soundFragment, PromptEntry promptEntry, int sequenceNumber, String sharerName, int durationSeconds) {
        this(soundFragment, promptEntry, sequenceNumber, sharerName, null, durationSeconds);
    }

    public SongEntry(SoundFragment soundFragment, PromptEntry promptEntry, int sequenceNumber, String sharerName,
                      String contributorEmail) {
        this(soundFragment, promptEntry, sequenceNumber, sharerName, contributorEmail, false);
    }

    public SongEntry(SoundFragment soundFragment, PromptEntry promptEntry, int sequenceNumber, String sharerName,
                      String contributorEmail, int durationSeconds) {
        this(soundFragment, promptEntry, sequenceNumber, sharerName, contributorEmail, durationSeconds, false);
    }

    public SongEntry(SoundFragment soundFragment, PromptEntry promptEntry, int sequenceNumber, String sharerName,
                      String contributorEmail, boolean priority) {
        this.promptEntry = promptEntry;
        this.id = UUID.randomUUID();
        this.soundFragment = soundFragment;
        this.sequenceNumber = sequenceNumber;
        this.durationSeconds = soundFragment.getLength() != null
                ? (int) soundFragment.getLength().toSeconds()
                : 180;
        this.sharerName = sharerName;
        this.contributorEmail = contributorEmail;
        this.priority = priority;
    }

    public SongEntry(SoundFragment soundFragment, PromptEntry promptEntry, int sequenceNumber, String sharerName,
                      String contributorEmail, int durationSeconds, boolean priority) {
        this.promptEntry = promptEntry;
        this.id = UUID.randomUUID();
        this.soundFragment = soundFragment;
        this.sequenceNumber = sequenceNumber;
        this.durationSeconds = durationSeconds;
        this.sharerName = sharerName;
        this.contributorEmail = contributorEmail;
        this.priority = priority;
    }

    public boolean isShared() {
        return sharerName != null;
    }
}
