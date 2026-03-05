package com.semantyca.djinn.dto;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class VoiceFilterDTO {
    private TTSEngineType engineType;
    private String gender;
    private List<LanguageTag> languages;
    private List<String> labels;
    private String searchTerm;

    public boolean isActivated() {
        return hasAnyFilter();
    }

    private boolean hasAnyFilter() {
        if (engineType != null) {
            return true;
        }
        if (gender != null && !gender.isEmpty()) {
            return true;
        }
        if (languages != null && !languages.isEmpty()) {
            return true;
        }
        if (labels != null && !labels.isEmpty()) {
            return true;
        }
        if (searchTerm != null && !searchTerm.isEmpty()) {
            return true;
        }
        return false;
    }
}
