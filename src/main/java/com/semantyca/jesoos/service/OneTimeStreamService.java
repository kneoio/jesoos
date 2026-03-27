package com.semantyca.jesoos.service;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.IUser;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.stream.OneTimeStreamDTO;
import com.semantyca.jesoos.dto.stream.StreamScheduleDTO;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.OneTimeStream;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.repository.OneTimeStreamRepository;
import com.semantyca.jesoos.repository.ScriptRepository;
import com.semantyca.jesoos.repository.brand.BrandRepository;
import com.semantyca.jesoos.service.stream.BrandPool;
import com.semantyca.jesoos.service.stream.AgendaService;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OneTimeStreamService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OneTimeStreamService.class);

    @Inject
    BrandRepository brandRepository;

    @Inject
    ScriptRepository scriptRepository;

    @Inject
    OneTimeStreamRepository oneTimeStreamRepository;

    @Inject
    BrandPool brandPool;

    @Inject
    JesoosConfig jesoosConfig;

    @Inject
    BrandService brandService;

    @Inject
    AgendaService agendaService;

    public Uni<List<OneTimeStreamDTO>> getAll(int limit, int offset) {
        return oneTimeStreamRepository.getAll(limit, offset)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    return Uni.combine().all().unis(
                            list.stream()
                                    .map(this::mapToDTO)
                                    .toList()
                    ).with(items ->
                            items.stream()
                                    .map(OneTimeStreamDTO.class::cast)
                                    .toList()
                    );
                });
    }

    public Uni<Integer> getAllCount() {
        return oneTimeStreamRepository.getAllCount();
    }

    public Uni<OneTimeStream> getById(UUID id) {
        return oneTimeStreamRepository.findById(id);
    }

    // fix return type
    public Uni<OneTimeStreamDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return oneTimeStreamRepository.findById(id)
                .chain(this::mapToDTO);
    }

    private Uni<OneTimeStreamDTO> mapToDTO(OneTimeStream doc) {
        OneTimeStreamDTO dto = new OneTimeStreamDTO();
        dto.setId(doc.getId());
        dto.setBaseBrandId(doc.getMasterBrand().getId());
        dto.setAiAgentId(doc.getAiAgentId());
        dto.setProfileId(doc.getProfileId());
        dto.setScripts(doc.getScripts());
        dto.setSlugName(doc.getSlugName());
        dto.setUserVariables(doc.getUserVariables());
        dto.setLocalizedName(doc.getLocalizedName());
        dto.setTimeZone(doc.getTimeZone() != null ? doc.getTimeZone().getId() : null);
        dto.setBitRate(doc.getBitRate());
        dto.setCreatedAt(doc.getCreatedAt());
        dto.setExpiresAt(doc.getExpiresAt());
        try {
            dto.setHlsUrl(URI.create(jesoosConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.m3u8").toURL());
            dto.setIceCastUrl(URI.create(jesoosConfig.getHost() + "/" + dto.getSlugName() + "/radio/icecast").toURL());
            dto.setMp3Url(URI.create(jesoosConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.mp3").toURL());
            dto.setMixplaUrl(URI.create("https://player.mixpla.io/?radio=" + dto.getSlugName()).toURL());
        } catch (
                MalformedURLException e) {
            throw new RuntimeException(e);
        }
        
        return brandPool.getLiveStatus(doc.getSlugName())
                .onItem().invoke(liveStatus -> dto.setStatus(liveStatus.getStatus()))
                .replaceWith(dto);
    }

    public Uni<OneTimeStream> getBySlugName(String slugName) {
        return oneTimeStreamRepository.getBySlugName(slugName);
    }

    public Uni<Void> delete(UUID id) {
        return oneTimeStreamRepository.findById(id)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(new RuntimeException("Stream not found"));
                    }
                    return brandPool.stopAndRemove(stream.getSlugName())
                            .chain(() -> oneTimeStreamRepository.delete(id));
                });
    }


    private StreamAgenda fromScheduleDTO(StreamScheduleDTO dto) {
        if (dto == null) {
            return null;
        }
        StreamAgenda schedule = new StreamAgenda(dto.getCreatedAt());
        if (dto.getScenes() != null) {
            for (StreamScheduleDTO.SceneScheduleDTO sceneDTO : dto.getScenes()) {
                LiveScene sceneEntry = fromSceneDTO(sceneDTO);
                schedule.addScene(sceneEntry);
            }
        }
        return schedule;
    }

    private LiveScene fromSceneDTO(StreamScheduleDTO.SceneScheduleDTO dto) {
        StreamScheduleDTO.ScenePlaylistRequest request = dto.getPlaylistRequest();
        LiveScene entry = new LiveScene(
                UUID.fromString(dto.getSceneId()),
                dto.getSceneTitle(),
                dto.getScheduledStartTime(),
                dto.getDurationSeconds(),
                dto.getOriginalStartTime(),
                request != null && request.getSourcing() != null ? WayOfSourcing.valueOf(request.getSourcing()) : null,
                request != null ? request.getPlaylistTitle() : null,
                request != null ? request.getArtist() : null,
                request != null ? request.getGenres() : null,
                request != null ? request.getLabels() : null,
                request != null && request.getPlaylistItemTypes() != null ? request.getPlaylistItemTypes().stream().map(PlaylistItemType::valueOf).toList() : null,
                request != null && request.getSourceTypes() != null ? request.getSourceTypes().stream().map(SourceType::valueOf).toList() : null,
                request != null ? request.getSearchTerm() : null,
                request != null ? request.getSoundFragments() : null,
                request != null ? request.getContentPrompts() : null,
                false,
                0.5,
                List.of()
        );
        if (dto.getSongs() != null) {
            for (StreamScheduleDTO.ScheduledSongDTO songDTO : dto.getSongs()) {
                entry.addSong(fromSongDTO(songDTO));
            }
        }
        return entry;
    }

    private SongEntry fromSongDTO(StreamScheduleDTO.ScheduledSongDTO dto) {
        SoundFragment soundFragment = new SoundFragment();
        soundFragment.setId(UUID.fromString(dto.getSongId()));
        soundFragment.setTitle(dto.getTitle());
        soundFragment.setArtist(dto.getArtist());
        return new SongEntry(
                UUID.fromString(dto.getId()),
                soundFragment,
                dto.getSequenceNumber(),
                dto.getEstimatedDurationSeconds()
        );
    }
}
