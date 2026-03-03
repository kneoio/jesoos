package com.semantyca.djinn.dto;

import lombok.Data;
import java.time.LocalTime;
import java.util.UUID;

@Data
public class SongPromptDTO {
    private UUID songId;
    private String draft;             // Song metadata/context for LLM
    private String prompt;            // LLM prompt template
    private String promptTitle;
    private String llmType;           // GROQ, OPENAI, ANTHROPIC
    private LocalTime startTime;
    private boolean oneTimeRun;
    private boolean podcast;          // Dialogue mode (multi-voice)
}
