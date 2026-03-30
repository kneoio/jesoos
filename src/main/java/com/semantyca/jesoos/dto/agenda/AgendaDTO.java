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
public class AgendaDTO {
    
    private String key;
    private String timezone;
    private String country;
    private LocalDateTime createdAt;
    private int totalScenes;
    private List<SceneDTO> scenes;
}
