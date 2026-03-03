package com.semantyca.djinn.dto.aiagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgentDTO extends AbstractDTO {
    @NotBlank
    private String name;
    private List<LanguagePreferenceDTO> preferredLang;
    @NotBlank
    private String llmType;
    private UUID copilot;
    private TTSSettingDTO ttsSetting;
    private List<UUID> labels;
}
