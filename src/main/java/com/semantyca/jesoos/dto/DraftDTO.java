package com.semantyca.jesoos.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
public class DraftDTO extends AbstractDTO {
    private String title;
    private String content;
    private String description;
    private String languageTag;
    private Integer archived;
    private boolean enabled;
    private boolean master;
    private boolean locked;
    private UUID masterId;
    private double version;
}
