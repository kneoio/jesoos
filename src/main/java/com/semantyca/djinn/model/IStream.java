package com.semantyca.djinn.model;

import com.semantyca.djinn.model.brand.Brand;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.UUID;

public interface IStream {

    Brand getMasterBrand();

    void setMasterBrand(Brand brand);

    UUID getId();

    String getSlugName();

    EnumMap<LanguageCode, String> getLocalizedName();

    ZoneId getTimeZone();

    long getBitRate();

    ManagedBy getManagedBy();

    StreamStatus getStatus();

    void setStatus(StreamStatus status);


    AiAgentStatus getAiAgentStatus();

    CountryCode getCountry();

    String getColor();

    default String getDescription() {
        return "";
    }

    void setColor(String s);

    void setPopularityRate(double popularityRate);

    void setCountry(CountryCode country);

    LocalDateTime getStartTime();

    double getPopularityRate();

    void setLastAgentContactAt(long l);

    long getLastAgentContactAt();

    LanguageTag getStreamLanguage();

    void setStreamLanguage(LanguageTag streamLanguage);
}
