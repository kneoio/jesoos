package com.semantyca.djinn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.Setter;

import java.net.URL;
import java.util.EnumMap;
import java.util.List;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RadioStationAiDTO {
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private String country;
    private URL hlsUrl;
    private URL mp3Url;
    private URL mixplaUrl;
    private String timeZone;
    private String description;
    private long bitRate;
    private StreamStatus streamStatus;
    private String djName;
    private String overriddenDjName;
    private String additionalUserInstruction;
    private List<LanguageTag> aiAgentLang;
}