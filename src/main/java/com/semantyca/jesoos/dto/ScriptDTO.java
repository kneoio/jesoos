package com.semantyca.jesoos.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.dto.AbstractDTO;
import com.semantyca.core.model.ScriptVariable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScriptDTO extends AbstractDTO {
    @NotBlank
    private String name;
    private String slugName;
    private UUID defaultProfileId;
    @NotBlank
    private String description;
    private Integer accessLevel = 0;
    @NotBlank
    private String languageTag;
    @NotNull
    private String timingMode;
    private List<UUID> labels;
    private List<UUID> brands;
    private List<SceneDTO> scenes;
    private List<ScriptVariable> requiredVariables;
}
