package com.semantyca.djinn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProfileDTO extends AbstractDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String description;
    private boolean explicitContent;
}