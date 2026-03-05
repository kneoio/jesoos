package com.semantyca.djinn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SceneDTO extends AbstractDTO {
    private UUID scriptId;
    private String title;
    private String scriptTitle;
    private double talkativity;
    private double podcastMode;
    private List<ScenePromptDTO> prompts;
    private StagePlaylistDTO stagePlaylist;
    private List<LocalTime> startTime;
    private int durationSeconds;
    private int seqNum;
    private List<Integer> weekdays;
    private boolean oneTimeRun;
}
