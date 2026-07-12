package com.semantyca.jesoos.model.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.util.WebHelper;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.brand.BrandScriptEntry;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.stream.IStreamer;
import com.semantyca.mixpla.model.stream.OtsDefinition;
import com.semantyca.officeframe.model.cnst.CountryCode;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Setter
@Getter
public class OneTimeStream extends AbstractStream {

    private static final Logger LOGGER = LoggerFactory.getLogger(OneTimeStream.class);

    private Script script;
    private Map<String, Object> userVariables;
    private AiAgentStatus aiAgentStatus;

    private UUID currentSceneId;
    private LocalDateTime lastDeliveryAt;
    private int lastDeliveredSongsDuration;
    private LocalDateTime scheduledOfflineAt;

    public OneTimeStream(Brand masterBrand, Script script, Map<String, Object> userVariables) {
        this(masterBrand, script, userVariables, null);
    }

    public OneTimeStream(Brand masterBrand, Script script, Map<String, Object> userVariables, UUID agentId) {
        this.masterBrand = masterBrand;
        this.streamId = UUID.randomUUID().toString();
        this.script = script;
        this.userVariables = userVariables;
        this.createdAt = LocalDateTime.now();
        this.managedBy = ManagedBy.DJ;
        String displayName = script.getName();
        if (displayName.length() > 40) {
            displayName = displayName.substring(0, 40) + "...";
        }
        this.slugName = WebHelper.generateSlug(displayName) + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
        if (this.slugName.length() > 50) {
            this.slugName = this.slugName.substring(0, 50);
        }
        EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
        localizedName.put(LanguageCode.en, displayName);
        this.localizedName = localizedName;
        this.timeZone = masterBrand.getTimeZone();
        this.color = WebHelper.generateRandomBrightColor();
        this.aiAgentId = agentId != null ? agentId : masterBrand.getAiAgentId();
        this.profileId = script.getDefaultProfileId();
        this.bitRate = masterBrand.getBitRate();
        this.aiOverriding = masterBrand.getAiOverriding();
        this.country = masterBrand.getCountry();
        this.scripts = List.of(new BrandScriptEntry(script.getId(), userVariables));
    }

    public OneTimeStream(OtsDefinition definition, Script script, Brand masterBrand, ZoneId fallbackTimeZone) {
        this.masterBrand = masterBrand;
        this.streamId = UUID.randomUUID().toString();
        this.script = script;
        this.userVariables = definition.getUserVariables() != null ? definition.getUserVariables() : Map.of();
        this.createdAt = LocalDateTime.now();
        this.managedBy = ManagedBy.DJ;
        this.slugName = definition.getSlugName();
        EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
        localizedName.put(LanguageCode.en, definition.getName());
        this.localizedName = localizedName;
        this.color = WebHelper.generateRandomBrightColor();
        this.profileId = script.getDefaultProfileId();
        this.scripts = List.of(new BrandScriptEntry(script.getId(), this.userVariables));

        if (masterBrand != null) {
            this.timeZone = masterBrand.getTimeZone();
            this.bitRate = masterBrand.getBitRate();
            this.aiOverriding = masterBrand.getAiOverriding();
            this.country = masterBrand.getCountry();
            this.aiAgentId = definition.getAgentId() != null ? definition.getAgentId() : masterBrand.getAiAgentId();
        } else {
            this.timeZone = fallbackTimeZone;
            this.bitRate = 64;
            this.aiOverriding = new AiOverriding();
            this.country = CountryCode.UNKNOWN;
            this.aiAgentId = definition.getAgentId();
        }
    }

    @Override
    public UUID getBrandId() {
        return masterBrand != null ? masterBrand.getId() : null;
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
