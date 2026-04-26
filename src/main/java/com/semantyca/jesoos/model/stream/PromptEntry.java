package com.semantyca.jesoos.model.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class PromptEntry {
    private UUID promptId;
    private LanguageCode language;
}
