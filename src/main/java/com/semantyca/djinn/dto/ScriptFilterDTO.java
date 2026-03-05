package com.semantyca.djinn.dto;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.IFilterDTO;
import com.semantyca.mixpla.model.cnst.SceneTimingMode;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class ScriptFilterDTO implements IFilterDTO {
    private boolean activated = false;

    @NotEmpty(message = "Labels list cannot be empty when provided")
    private List<UUID> labels;

    private SceneTimingMode timingMode;

    private LanguageTag languageTag;

    private String searchTerm;

    @Override
    public boolean isActivated() {
        return activated || hasAnyFilter();
    }

    @Override
    public boolean hasAnyFilter() {
        return (labels != null && !labels.isEmpty()) ||
                timingMode != null ||
                languageTag != null ||
                (searchTerm != null && !searchTerm.trim().isEmpty());
    }
}
