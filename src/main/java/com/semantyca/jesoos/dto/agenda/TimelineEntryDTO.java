package com.semantyca.jesoos.dto.agenda;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEntryDTO {
    private String id;
    private int sequenceNumber;
    private LocalDateTime scheduledEmissionTime;
    private List<SongDTO> songs;
    private int durationSeconds;
    private String mixingStrategy;
    private boolean hasIntro;
    private boolean hasJingle;
    private boolean generated;
    private String boostType;
    private int batchId;
    private String status;
    private List<StatusRecordDTO> statusHistory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusRecordDTO {
        private String status;
        private Instant at;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SongDTO {
        private String songId;
        private String songTitle;
        private String artist;
        private int durationSeconds;
        private String language;
        private String promptTitle;
        private boolean shared;
        private String sharerName;
    }
}
