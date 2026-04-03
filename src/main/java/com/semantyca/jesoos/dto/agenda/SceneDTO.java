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
public class SceneDTO {
    
    private String id;
    private String title;
    private LocalDateTime firstEmissionTime;
    private LocalDateTime lastEmissionTime;
    private int durationSeconds;
    private int totalSongs;
    private boolean timelineBuilt;
    private int fitSeconds;
    private List<TimelineEntryDTO> timeline;
}
