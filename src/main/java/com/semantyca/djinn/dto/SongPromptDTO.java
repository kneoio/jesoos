package com.semantyca.djinn.dto;

import com.semantyca.mixpla.model.cnst.LlmType;
import com.semantyca.mixpla.model.cnst.PromptType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SongPromptDTO {
    private UUID songId;
    private String draft;
    private String prompt;
    private PromptType promptType;
    private LlmType llmType;
    private LocalTime startTime;
    private boolean podcast;
    private String promptTitle;
    private int songDurationSeconds;

    public SongPromptDTO(UUID songId, String draft, String prompt, PromptType promptType,
                         LlmType llmType, LocalTime startTime, boolean podcast, String promptTitle) {
        this.songId = songId;
        this.draft = draft;
        this.prompt = prompt;
        this.promptType = promptType;
        this.llmType = llmType;
        this.startTime = startTime;
        this.podcast = podcast;
        this.promptTitle = promptTitle;
        this.songDurationSeconds = 0;
    }
}