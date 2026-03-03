package com.semantyca.djinn.dto.aiagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TTSSettingDTO {
    private VoiceDTO dj;
    private VoiceDTO copilot;
    private VoiceDTO newsReporter;
    private VoiceDTO weatherReporter;
}
