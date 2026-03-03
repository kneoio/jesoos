package com.semantyca.djinn.model.brand;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BrandScriptEntry {
    private UUID scriptId;
    private Map<String, Object> userVariables;
}
