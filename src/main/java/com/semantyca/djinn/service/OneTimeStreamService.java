package com.semantyca.djinn.service;

import com.semantyca.djinn.config.DjinnConfig;
import com.semantyca.djinn.dto.radiostation.OneTimeStreamRunReqDTO;
import com.semantyca.djinn.dto.stream.OneTimeStreamDTO;
import com.semantyca.djinn.dto.stream.StreamScheduleDTO;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.OneTimeStream;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.model.stream.StreamAgenda;
import com.semantyca.djinn.repository.OneTimeStreamRepository;
import com.semantyca.djinn.repository.ScriptRepository;
import com.semantyca.djinn.repository.brand.BrandRepository;
import com.semantyca.djinn.service.stream.BrandPool;
import com.semantyca.djinn.service.stream.StreamAgendaService;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
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
    DjinnConfig djinnConfig;

    @Inject
    BrandService brandService;

    @Inject
    StreamAgendaService streamAgendaService;


    public Uni<OneTimeStreamRunReqDTO> populateFromSlugName(OneTimeStreamRunReqDTO dto, IUser user) {
        if (dto.getSlugName() == null || dto.getSlugName().isEmpty()) {
            return Uni.createFrom().item(dto);
        }

        return brandService.getBySlugName(dto.getSlugName())
                .chain(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + dto.getSlugName()));
                    }

                    dto.setBaseBrandId(brand.getId());
                    dto.setAiAgentId(brand.getAiAgentId());

                    return scriptRepository.findById(dto.getScriptId(), user, false)
                            .chain(script -> {
                                if (script == null) {
                                    return Uni.createFrom().failure(new IllegalArgumentException("Script not found"));
                                }

                                dto.setProfileId(script.getDefaultProfileId());

                                if (dto.getSchedule() == null) {
                                    return streamAgendaService.getStreamScheduleDTO(brand.getId(), script.getId(), user)
                                            .map(schedule -> {
                                                dto.setSchedule(schedule);
                                                return dto;
                                            });
                                }

                                return Uni.createFrom().item(dto);
                            });
                });
    }

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
        dto.setStreamSchedule(streamAgendaService.toScheduleDTO(doc.getAgenda()));
        dto.setCreatedAt(doc.getCreatedAt());
        dto.setExpiresAt(doc.getExpiresAt());
        try {
            dto.setHlsUrl(URI.create(djinnConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.m3u8").toURL());
            dto.setIceCastUrl(URI.create(djinnConfig.getHost() + "/" + dto.getSlugName() + "/radio/icecast").toURL());
            dto.setMp3Url(URI.create(djinnConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.mp3").toURL());
            dto.setMixplaUrl(URI.create("https://player.mixpla.io/?radio=" + dto.getSlugName()).toURL());
        } catch (
                MalformedURLException e) {
            throw new RuntimeException(e);
        }
        
        return brandPool.getLiveStatus(doc.getSlugName())
                .onItem().invoke(liveStatus -> dto.setStatus(liveStatus.getStatus()))
                .replaceWith(dto);
    }

    public Uni<OneTimeStream> start(OneTimeStream stream) {
        LOGGER.info("OneTimeStream: Initializing stream slugName={}", stream.getSlugName());
        String streamSlugName = stream.getSlugName();
        return brandPool.initializeStream(stream)
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to initialize stream: {}", streamSlugName, failure);
                    brandPool.get(streamSlugName)
                            .subscribe().with(
                                    station -> {
                                        if (station != null) {
                                            station.setStatus(StreamStatus.SYSTEM_ERROR);
                                            LOGGER.warn("Stream {} status set to SYSTEM_ERROR due to initialization failure", streamSlugName);
                                        }
                                    },
                                    error -> LOGGER.error("Failed to get station {} to set error status: {}", streamSlugName, error.getMessage(), error)
                            );
                })
                .invoke(liveStream -> {
                    if (liveStream != null) {
                        stream.setStatus(liveStream.getStatus());
                    }
                })
                .replaceWith(stream);
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
                dto.getOriginalEndTime(),
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

    private PendingSongEntry fromSongDTO(StreamScheduleDTO.ScheduledSongDTO dto) {
        SoundFragment soundFragment = new SoundFragment();
        soundFragment.setId(UUID.fromString(dto.getSongId()));
        soundFragment.setTitle(dto.getTitle());
        soundFragment.setArtist(dto.getArtist());
        return new PendingSongEntry(
                UUID.fromString(dto.getId()),
                soundFragment,
                dto.getSequenceNumber(),
                dto.getEstimatedDurationSeconds()
        );
    }
}
