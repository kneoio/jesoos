package com.semantyca.djinn.dto;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.IFilterDTO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class DraftFilterDTO implements IFilterDTO {
    private boolean activated = false;

    private LanguageTag languageTag;
    private Integer archived;
    private boolean enabled;
    private boolean master;
    private boolean locked;

    @Override
    public boolean isActivated() {
        return activated || hasAnyFilter();
    }

    @Override
    public boolean hasAnyFilter() {
        return languageTag != null ||
               archived != null || 
               enabled || 
               master || 
               locked;
    }
}