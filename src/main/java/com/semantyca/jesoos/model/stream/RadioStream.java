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
        this.masterBrand = brand;
        this.id = brand.getId();
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
        this.scripts = brand.getScripts();
    }

    @Override
    public String toString() {
        return String.format("RadioStream[id: %s, slug: %s, baseBrand: %s]", id, slugName, masterBrand.getSlugName());
    }

    @Override
    public UUID getMasterBrandId() {
        return id;
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
