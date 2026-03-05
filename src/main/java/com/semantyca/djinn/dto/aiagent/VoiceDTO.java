package com.semantyca.djinn.dto.aiagent;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class VoiceDTO {
    private String id;
    private String name;
    private TTSEngineType engineType;
    private String gender;
    private LanguageTag language;

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> labels;
}
