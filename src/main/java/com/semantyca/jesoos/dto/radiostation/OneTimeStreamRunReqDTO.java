package com.semantyca.jesoos.dto.radiostation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.jesoos.dto.stream.StreamScheduleDTO;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.net.URL;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OneTimeStreamRunReqDTO {
    private String slugName;
    private String email;
    private String confirmationCode;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    @NotNull
    private UUID baseBrandId;
    @NotNull
    private UUID aiAgentId;
    @NotNull
    private UUID profileId;
    @NotNull
    private UUID scriptId;
    @NotNull
    private Map<String, Object> userVariables;
    @NotNull
    private StreamScheduleDTO schedule;
    private long bitRate;
    private boolean startImmediately = false;

    private URL hlsUrl;
    private URL mixplaUrl;
}
