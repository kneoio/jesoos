package com.semantyca.jesoos.model.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.brand.BrandScriptEntry;
import com.semantyca.mixpla.model.brand.ProfileOverriding;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.stream.IStream;
import com.semantyca.officeframe.model.cnst.CountryCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Setter
@Getter
public abstract class AbstractStream implements IStream, ILiveAgenda {
    protected UUID id;
    protected Brand masterBrand;
    protected String slugName;
    protected EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    protected StreamStatus status = StreamStatus.OFF_LINE;
    protected List<StatusChangeRecord> statusHistory = new LinkedList<>();
    protected LocalDateTime startTime;
    protected ZoneId timeZone;
    protected long bitRate;
    protected ManagedBy managedBy = ManagedBy.ITSELF;
    protected String color;
    protected CountryCode country;
    protected double popularityRate = 5;
    protected UUID aiAgentId;
    protected UUID profileId;
    protected AiOverriding aiOverriding;
    protected List<BrandScriptEntry> scripts;
    protected ProfileOverriding profileOverriding;
    protected LocalDateTime createdAt;
    protected LocalDateTime expiresAt;
    protected StreamAgenda agenda;
    protected AiAgentStatus aiAgentStatus;
    protected long lastAgentContactAt;
    protected LanguageTag broadcastingLanguage;
    protected LanguageTag streamLanguage;
    protected final Map<UUID, Set<UUID>> fetchedSongsByScene = new HashMap<>();

    @Override
    public void setStatus(StreamStatus newStatus) {
        if (this.status != newStatus) {
            StatusChangeRecord record = new StatusChangeRecord(
                    LocalDateTime.now(timeZone),
                    this.status,
                    newStatus
            );
            if (statusHistory.isEmpty()) {
                startTime = record.timestamp();
            }
            statusHistory.add(record);
            this.status = newStatus;
        }
    }

    public Set<UUID> getFetchedSongsInScene(UUID sceneId) {
        return fetchedSongsByScene.computeIfAbsent(sceneId, k -> new HashSet<>());
    }

    public void clearSceneState(UUID sceneId) {
        fetchedSongsByScene.remove(sceneId);
    }
}
