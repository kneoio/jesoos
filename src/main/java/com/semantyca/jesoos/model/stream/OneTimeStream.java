package com.semantyca.jesoos.model.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.util.WebHelper;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.brand.BrandScriptEntry;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.stream.IStreamer;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
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
    private StreamDeliveryState deliveryState;

    private UUID currentSceneId;
    private LocalDateTime lastDeliveryAt;
    private int lastDeliveredSongsDuration;
    private LocalDateTime scheduledOfflineAt;

    public OneTimeStream(Brand masterBrand, Script script, Map<String, Object> userVariables) {
        this.masterBrand = masterBrand;
        this.id = UUID.randomUUID();
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
        this.aiAgentId = masterBrand.getAiAgentId();
        this.profileId = script.getDefaultProfileId();
        this.bitRate = masterBrand.getBitRate();
        this.aiOverriding = masterBrand.getAiOverriding();
        this.country = masterBrand.getCountry();
        this.scripts = List.of(new BrandScriptEntry(script.getId(), userVariables));
    }

    public LiveScene findActiveScene(int prepareMinutesInAdvance) {
        List<LiveScene> scenes = agenda.getLiveScenes();

        boolean anySceneStarted = scenes.stream()
                .anyMatch(scene -> scene.getActualStartTime() != null);

        if (!anySceneStarted) {
            return scenes.isEmpty() ? null : scenes.getFirst();
        }

        for (LiveScene entry : scenes) {
            if (entry.getActualStartTime() != null && entry.getActualEndTime() == null) {
                return entry;
            }
            if (entry.getActualStartTime() == null) {
                return entry;
            }
        }
        return null;
    }

    public boolean isCompleted() {
        return agenda.getLiveScenes().stream()
                .allMatch(e -> e.getActualStartTime() != null && e.getActualEndTime() != null);
    }

    @Override
    public UUID getMasterBrandId() {
        return masterBrand.getId();
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
