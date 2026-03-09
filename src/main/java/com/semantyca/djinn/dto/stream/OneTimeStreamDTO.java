package com.semantyca.djinn.dto.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.mixpla.model.brand.BrandScriptEntry;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URL;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OneTimeStreamDTO {
    private UUID id;
    private String slugName;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private UUID aiAgentId;
    private UUID profileId;
    private List<BrandScriptEntry> scripts;
    private String timeZone;
    private long bitRate;
    private StreamStatus status = StreamStatus.OFF_LINE;
    private UUID baseBrandId;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private StreamScheduleDTO streamSchedule;
    private Map<String, Object> userVariables;
    private URL hlsUrl;
    private URL iceCastUrl;
    private URL mp3Url;
    private URL mixplaUrl;

}
