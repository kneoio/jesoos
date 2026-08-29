package com.semantyca.jesoos.model.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.mixpla.model.CustomAction;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class PromptEntry {
    private UUID promptId;
    private LanguageCode language;
    private CustomAction customAction;

    public boolean isAction() {
        return customAction != null;
    }

    public boolean hasIntroSource() {
        return isAction() || promptId != null;
    }
}
