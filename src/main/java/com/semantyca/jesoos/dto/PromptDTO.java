package com.semantyca.jesoos.dto;

import com.semantyca.core.dto.AbstractDTO;
import com.semantyca.mixpla.model.cnst.PromptType;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class PromptDTO extends AbstractDTO {
    private boolean enabled;
    private String prompt;
    private String description;
    private PromptType promptType;
    private String languageTag;
    private boolean master;
    private boolean locked;
    private String title;
    private JsonObject backup;
    private boolean podcast;
    private UUID draftId;
    private UUID masterId;
    private double version;
}
