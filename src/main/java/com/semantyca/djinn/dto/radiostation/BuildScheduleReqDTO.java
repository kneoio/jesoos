package com.semantyca.djinn.dto.radiostation;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class BuildScheduleReqDTO {
    @NotNull
    private UUID baseBrandId;
    @NotNull
    private UUID scriptId;
}
