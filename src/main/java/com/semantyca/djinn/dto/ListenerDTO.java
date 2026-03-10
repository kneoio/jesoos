package com.semantyca.djinn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.localization.LanguageCode;
import jakarta.validation.constraints.Email;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListenerDTO extends AbstractReferenceDTO {
    private long userId;
    @Email
    private String email;
    private String slugName;
    @Builder.Default
    private EnumMap<LanguageCode, Set<String>> nickName = new EnumMap<>(LanguageCode.class);
    @Builder.Default
    private Map<String, String> userData = new HashMap<>();
    private Integer archived;
    private List<UUID> listenerOf;
    private List<UUID> labels;
}