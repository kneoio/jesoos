package com.semantyca.jesoos.dto.agenda;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private int batchId;
    private String status;

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
    }
}
