package com.semantyca.djinn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenePromptDTO {
    private UUID promptId;
    private boolean active = true;
    private int rank = 0;
    private BigDecimal weight = BigDecimal.valueOf(0.5);
}
