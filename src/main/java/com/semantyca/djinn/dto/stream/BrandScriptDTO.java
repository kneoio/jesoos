package com.semantyca.djinn.dto.stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.semantyca.djinn.dto.ScriptDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
public class BrandScriptDTO {
    private UUID id;
    private UUID defaultBrandId;
    private int rank;
    private boolean active;
    @JsonProperty("script")
    private ScriptDTO script;
    private List<UUID> representedInBrands;
}
