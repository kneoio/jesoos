package com.semantyca.jesoos.model.stream;

import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.stream.IStreamer;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class RadioStream extends AbstractStream {

    public RadioStream(Brand brand) {
        this.brand = brand;
        this.streamId = brand.getId().toString();
        this.slugName = brand.getSlugName();
        this.localizedName = new EnumMap<>(brand.getLocalizedName());
        this.timeZone = brand.getTimeZone();
        this.bitRate = brand.getBitRate();
        this.managedBy = brand.getManagedBy();
        this.createdAt = LocalDateTime.now();
        this.popularityRate = brand.getPopularityRate();
        this.timeZone = brand.getTimeZone();
        this.color = brand.getColor();
        this.aiAgentId = brand.getAiAgentId();
        this.profileId = brand.getProfileId();
        this.bitRate = brand.getBitRate();
        this.aiOverriding = brand.getAiOverriding();
        this.profileOverriding = brand.getProfileOverriding();
        this.country = brand.getCountry();
        this.scripts = brand.getScriptIds();
    }

    @Override
    public String toString() {
        return String.format("RadioStream[streamId: %s, slug: %s, baseBrand: %s]", streamId, slugName, brand.getSlugName());
    }

    @Override
    public UUID getBrandId() {
        return brand.getId();
    }

    @Override
    public IStreamer getStreamer() {
        return null;
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
