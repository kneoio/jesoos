package com.semantyca.djinn.dto.radiostation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class BrandScriptEntryDTO {
    private UUID scriptId;
    private Map<String, Object> userVariables;
}
