package com.semantyca.jesoos.model.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.brand.BrandScriptEntry;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.stream.IStreamer;
import com.semantyca.mixpla.model.stream.OtsDefinition;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Setter
@Getter
public class OneTimeStream extends AbstractStream {
    private Script script;
    private AiAgentStatus aiAgentStatus;
    private UUID currentSceneId;
    private LocalDateTime lastDeliveryAt;
    private int lastDeliveredSongsDuration;
    private LocalDateTime scheduledOfflineAt;
    private boolean isSynthetic;
    private String chatContext;
    private Map<UUID, Integer> sceneDurations;

    public OneTimeStream(OtsDefinition definition, Script script, Brand brand, ZoneId fallbackTimeZone) {
        if (brand == null) {
            this.isSynthetic = true;
            this.brand = new SyntheticBrand(fallbackTimeZone, definition.getAuthor());
        } else {
            this.isSynthetic = false;
            this.brand = brand;
        }
        this.streamId = UUID.randomUUID().toString();
        this.script = script;
        this.userVariables = definition.getUserVariables() != null ? definition.getUserVariables() : Map.of();
        this.createdAt = LocalDateTime.now();
        this.managedBy = ManagedBy.DJ;
        this.slugName = definition.getSlugName();
        EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
        localizedName.put(LanguageCode.en, definition.getName());
        this.localizedName = localizedName;
        this.color = definition.getColor();
        this.profileId = script.getDefaultProfileId();
        this.scripts = List.of(new BrandScriptEntry(script.getId(), this.userVariables));
        this.timeZone = this.brand.getTimeZone();
        this.bitRate = this.brand.getBitRate();
        this.aiOverriding = this.brand.getAiOverriding();
        this.country = this.brand.getCountry();
        this.aiAgentId = definition.getAgentId() != null ? definition.getAgentId() : this.brand.getAiAgentId();
        this.brand.setAiAgentId(this.aiAgentId);
        this.chatContext = definition.getChatContext();
        this.sceneDurations = definition.getSceneDurations() != null ? definition.getSceneDurations() : Map.of();
    }

    @Override
    public UUID getBrandId() {
        return null;
    }

    @Override
    public IStreamer getStreamer() {
        return null;
    }

    @Override
    public String getDescription() {
        return super.getDescription();
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
