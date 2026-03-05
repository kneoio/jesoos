package com.semantyca.djinn.model.stream;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class StreamDeliveryState {
    private UUID sceneId;
    private LocalDateTime sceneStart;
    private LocalDateTime sceneEnd;
    private int deliveredSongIndex;
    private LocalDateTime lastDeliveryAt;

    void reset(LiveScene entry) {
        this.sceneId = entry.getSceneId();
        this.sceneStart = entry.getScheduledStartTime();
        this.sceneEnd = entry.getScheduledEndTime();
        this.deliveredSongIndex = 0;
        this.lastDeliveryAt = LocalDateTime.now();
    }

    boolean isExpired(LocalDateTime now) {
        return now.isAfter(sceneEnd);
    }
}
