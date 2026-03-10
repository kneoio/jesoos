package com.semantyca.djinn.service.stream;

import com.semantyca.djinn.dto.stream.StreamScheduleDTO;
import com.semantyca.djinn.model.stream.ILiveAgenda;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.model.stream.StreamAgenda;
import com.semantyca.djinn.service.BrandService;
import com.semantyca.djinn.service.SceneService;
import com.semantyca.djinn.service.ScriptService;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class StreamAgendaService {
    private static final Logger LOGGER = Logger.getLogger(StreamAgendaService.class);
    private static final int AVG_DJ_INTRO_SECONDS = 30;

    @Inject
    BrandService brandService;

    @Inject
    ScriptService scriptService;

    @Inject
    ScheduleSongSupplier scheduleSongSupplier;

    @Inject
    SceneService sceneService;

    private final BrandPool brandPool;

    @Inject
    public StreamAgendaService(BrandPool brandPool) {
        this.brandPool = brandPool;
    }

    public Uni<ILiveAgenda> buildRadioLiveAgenda(String brand) {
        return brandPool.initializeRadio(brand)
                .onFailure().invoke(f ->
                        brandPool.get(brand)
                                .subscribe().with(s -> {
                                    if (s != null) s.setStatus(StreamStatus.SYSTEM_ERROR);
                                })
                );
    }

    public Uni<StreamAgenda> buildRadioLiveAgenda(UUID brandId, UUID scriptId, IUser user) {
        return brandService.getById(brandId)
                .chain(sourceBrand ->
                        scriptService.getById(scriptId, user)
                                .chain(script ->
                                        sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                                                .map(list -> new TreeSet<>(
                                                        Comparator.comparingInt(Scene::getSeqNum)
                                                                .thenComparing(Scene::getId)
                                                ) {{
                                                    addAll(list);
                                                }})
                                                .invoke(script::setScenes)
                                                .chain(x -> buildRadioAgenda(script, sourceBrand, scheduleSongSupplier))
                                )
                );
    }

    public Uni<StreamAgenda> buildRadioAgenda(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        ZoneId brandZone = sourceBrand.getTimeZone();
        LocalDateTime brandNow = LocalDateTime.now(brandZone);
        StreamAgenda schedule = new StreamAgenda(LocalDateTime.now());
        schedule.setTimeZone(sourceBrand.getTimeZone());

        NavigableSet<Scene> scenes = script.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        record SceneTimeSlot(Scene scene, LocalTime startTime) {}

        List<SceneTimeSlot> timeSlots = new ArrayList<>();
        for (Scene scene : scenes) {
            if (scene.getStartTime() != null && !scene.getStartTime().isEmpty()) {
                for (LocalTime startTime : scene.getStartTime()) {
                    timeSlots.add(new SceneTimeSlot(scene, startTime));
                }
            }
        }

        timeSlots.sort(Comparator.comparing(SceneTimeSlot::startTime));

        if (timeSlots.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        LocalDateTime todayAt6 = brandNow.toLocalDate().atTime(6, 0);
        LocalDateTime broadcastDayStart = brandNow.isBefore(todayAt6) ? todayAt6 : todayAt6.plusDays(1);
        LocalTime firstSceneTime = timeSlots.getFirst().startTime();
        LocalDateTime todayFirstScene = broadcastDayStart.toLocalDate().atTime(firstSceneTime);
        boolean nowIsBeforeFirstScene = brandNow.isBefore(todayFirstScene);

        List<Uni<LiveScene>> sceneUnis = new ArrayList<>();

        for (int i = 0; i < timeSlots.size(); i++) {
            SceneTimeSlot slot = timeSlots.get(i);
            Scene scene = slot.scene();
            LocalTime sceneOriginalStart = slot.startTime();

            int nextIndex = (i + 1) % timeSlots.size();
            LocalTime sceneOriginalEnd = timeSlots.get(nextIndex).startTime();

            int finalDurationSeconds = calculateDurationUntilNext(sceneOriginalStart, sceneOriginalEnd);

            LocalDateTime finalSceneStartTime = broadcastDayStart.toLocalDate().atTime(sceneOriginalStart);
            if (finalSceneStartTime.isBefore(broadcastDayStart)) {
                finalSceneStartTime = finalSceneStartTime.plusDays(1);
            }
            boolean isLast = (i == timeSlots.size() - 1);
            if (isLast && nowIsBeforeFirstScene) {
                LocalDateTime sceneToday = broadcastDayStart.toLocalDate().minusDays(1).atTime(sceneOriginalStart);
                finalSceneStartTime = brandNow.isBefore(sceneToday)
                        ? sceneToday.minusDays(1)
                        : sceneToday;
            }
            final LocalDateTime capturedSceneStartTime = finalSceneStartTime;

            sceneUnis.add(
                    fetchSongsForSceneWithDuration(sourceBrand, scene, finalDurationSeconds, songSupplier)
                            .map(songs -> {
                                LiveScene entry = new LiveScene(
                                        scene.getId(),
                                        scene.getTitle(),
                                        capturedSceneStartTime,
                                        finalDurationSeconds,
                                        sceneOriginalStart,
                                        sceneOriginalEnd,
                                        scene.getPlaylistRequest().getSourcing(),
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getTitle() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getArtist() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getGenres() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getLabels() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getType() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSource() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSearchTerm() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSoundFragments() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getContentPrompts() : null,
                                        scene.isOneTimeRun(),
                                        scene.getTalkativity(),
                                        scene.getIntroPrompts()
                                );
                                int sequenceNumber = 0;
                                for (SoundFragment song : songs) {
                                    PendingSongEntry songEntry = new PendingSongEntry(song, sequenceNumber++);
                                    entry.addSong(songEntry);
                                }
                                return entry;
                            })
            );
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    entries.forEach(schedule::addScene);
                    return schedule;
                });
    }

    public Uni<StreamScheduleDTO> getStreamScheduleDTO(UUID brandId, UUID scriptId, IUser user) {
        return buildStreamSchedule(brandId, scriptId, user)
                .map(this::toScheduleDTO);
    }

    public Uni<StreamAgenda> buildStreamSchedule(UUID brandId, UUID scriptId, IUser user) {
        return brandService.getById(brandId)
                .chain(sourceBrand ->
                        scriptService.getById(scriptId, user)
                                .chain(script ->
                                        sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                                                .map(list -> new TreeSet<>(
                                                        Comparator.comparingInt(Scene::getSeqNum)
                                                                .thenComparing(Scene::getId)
                                                ) {{
                                                    addAll(list);
                                                }})
                                                .invoke(script::setScenes)
                                                .chain(x -> build(script, sourceBrand, scheduleSongSupplier))
                                )
                );
    }

    public Uni<StreamAgenda> build(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        StreamAgenda schedule = new StreamAgenda(LocalDateTime.now());

        NavigableSet<Scene> scenes = script.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        List<Uni<LiveScene>> sceneUnis = new ArrayList<>();
        LocalDateTime sceneStartTime = LocalDateTime.now();

        for (Scene scene : scenes) {
            LocalDateTime finalSceneStartTime = sceneStartTime;
            sceneUnis.add(
                    fetchSongsForScene(sourceBrand, scene, songSupplier)
                            .map(songs -> {
                                LiveScene entry = new LiveScene(scene, finalSceneStartTime);
                                int sequenceNumber = 0;
                                for (SoundFragment song : songs) {
                                    PendingSongEntry songEntry = new PendingSongEntry(song, sequenceNumber++);
                                    entry.addSong(songEntry);
                                }
                                return entry;
                            })
            );
            sceneStartTime = sceneStartTime.plusSeconds(scene.getDurationSeconds());
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    entries.forEach(schedule::addScene);
                    return schedule;
                });
    }

    private Uni<List<SoundFragment>> fetchSongsForScene(Brand brand, Scene scene, ScheduleSongSupplier songSupplier) {
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();
        if (playlistRequest != null && playlistRequest.getSourcing() == WayOfSourcing.GENERATED) {
            return Uni.createFrom().item(List.of());
        }

        int sceneDurationSeconds = scene.getDurationSeconds();
        int maxSongsNeeded = (int) Math.ceil(sceneDurationSeconds / 120.0 * 1.5) + 2;

        Uni<List<SoundFragment>> songsPoolUni;
        if (playlistRequest == null) {
            songsPoolUni = songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
        } else {
            WayOfSourcing sourcing = playlistRequest.getSourcing();
            if (sourcing == null) {
                songsPoolUni = songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
            } else {
                songsPoolUni = switch (sourcing) {
                    case QUERY -> {
                        PlaylistRequest req = new PlaylistRequest();
                        req.setSearchTerm(playlistRequest.getSearchTerm());
                        req.setGenres(playlistRequest.getGenres());
                        req.setLabels(playlistRequest.getLabels());
                        req.setType(playlistRequest.getType());
                        req.setSource(playlistRequest.getSource());
                        yield songSupplier.getSongsByQuery(brand.getId(), req, maxSongsNeeded);
                    }
                    case STATIC_LIST -> songSupplier.getSongsFromStaticList(playlistRequest.getSoundFragments(), maxSongsNeeded);
                    default -> songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
                };
            }
        }

        return songsPoolUni.map(songsPool -> selectSongsToFitDuration(songsPool, sceneDurationSeconds));
    }

    private List<SoundFragment> selectSongsToFitDuration(List<SoundFragment> songsPool, int sceneDurationSeconds) {
        if (songsPool.isEmpty()) {
            return songsPool;
        }

        List<SoundFragment> selectedSongs = new ArrayList<>();
        int totalTimeUsed = 0;

        for (SoundFragment song : songsPool) {
            int songDurationSeconds = song.getLength() != null ? (int) song.getLength().toSeconds() : 180;
            int timeWithIntro = songDurationSeconds + AVG_DJ_INTRO_SECONDS;

            if (totalTimeUsed + timeWithIntro <= sceneDurationSeconds) {
                selectedSongs.add(song);
                totalTimeUsed += timeWithIntro;
            } else if (selectedSongs.isEmpty()) {
                selectedSongs.add(song);
                totalTimeUsed += timeWithIntro;
                break;
            } else {
                int gap = sceneDurationSeconds - totalTimeUsed;
                if (gap > 60) {
                    selectedSongs.add(song);
                    totalTimeUsed += timeWithIntro;
                }
            }
        }


        return selectedSongs;
    }

    private int calculateDurationUntilNext(LocalTime start, LocalTime next) {
        int startSeconds = start.toSecondOfDay();
        int nextSeconds = next.toSecondOfDay();
        if (nextSeconds > startSeconds) {
            return nextSeconds - startSeconds;
        } else {
            return (24 * 60 * 60 - startSeconds) + nextSeconds;
        }
    }

    private Uni<List<SoundFragment>> fetchSongsForSceneWithDuration(Brand brand, Scene scene, int durationSeconds, ScheduleSongSupplier songSupplier) {
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();
        if (playlistRequest != null && playlistRequest.getSourcing() == WayOfSourcing.GENERATED) {
            return Uni.createFrom().item(List.of());
        }

        int maxSongsNeeded = (durationSeconds / 120) + 2;

        Uni<List<SoundFragment>> songsPoolUni;
        if (playlistRequest == null) {
            songsPoolUni = songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
        } else {
            WayOfSourcing sourcing = playlistRequest.getSourcing();
            if (sourcing == null) {
                songsPoolUni = songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
            } else {
                songsPoolUni = switch (sourcing) {
                    case QUERY -> {
                        PlaylistRequest req = new PlaylistRequest();
                        req.setSearchTerm(playlistRequest.getSearchTerm());
                        req.setGenres(playlistRequest.getGenres());
                        req.setLabels(playlistRequest.getLabels());
                        req.setType(playlistRequest.getType());
                        req.setSource(playlistRequest.getSource());
                        yield songSupplier.getSongsByQuery(brand.getId(), req, maxSongsNeeded);
                    }
                    case STATIC_LIST -> songSupplier.getSongsFromStaticList(playlistRequest.getSoundFragments(), maxSongsNeeded);
                    default -> songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
                };
            }
        }

        return songsPoolUni.map(songsPool -> selectSongsToFitDurationWithTalkativity(songsPool, durationSeconds, scene.getTalkativity()));
    }

    private List<SoundFragment> selectSongsToFitDurationWithTalkativity(List<SoundFragment> songsPool, int sceneDurationSeconds, double talkativity) {
        if (songsPool.isEmpty()) {
            return songsPool;
        }

        int effectiveMusicTime = (int) (sceneDurationSeconds * (1 - talkativity * 0.3));

        List<SoundFragment> selectedSongs = new ArrayList<>();
        java.util.Set<UUID> addedSongIds = new java.util.HashSet<>();
        int totalTimeUsed = 0;

        for (SoundFragment song : songsPool) {
            if (addedSongIds.contains(song.getId())) {
                continue;
            }

            int songDurationSeconds = song.getLength() != null ? (int) song.getLength().toSeconds() : 180;
            int timeWithIntro = songDurationSeconds + AVG_DJ_INTRO_SECONDS;

            if (totalTimeUsed + timeWithIntro <= effectiveMusicTime) {
                selectedSongs.add(song);
                addedSongIds.add(song.getId());
                totalTimeUsed += timeWithIntro;
            } else if (selectedSongs.isEmpty()) {
                selectedSongs.add(song);
                addedSongIds.add(song.getId());
                break;
            } else {
                break;
            }
        }

        LOGGER.debugf("RadioStream scene duration: {}s, effective music time: {}s (talkativity: {}), Selected {} songs with total time: {}s",
                sceneDurationSeconds, effectiveMusicTime, talkativity, selectedSongs.size(), totalTimeUsed);

        return selectedSongs;
    }
    public StreamScheduleDTO toScheduleDTO(StreamAgenda schedule) {
        if (schedule == null) {
            return null;
        }
        StreamScheduleDTO dto = new StreamScheduleDTO();
        dto.setCreatedAt(schedule.getCreatedAt());
        dto.setEstimatedEndTime(schedule.getEstimatedEndTime());
        dto.setTotalScenes(schedule.getTotalScenes());
        dto.setTotalSongs(schedule.getTotalSongs());

        List<StreamScheduleDTO.SceneScheduleDTO> sceneDTOs = schedule.getLiveScenes().stream()
                .map(this::toSceneDTO)
                .collect(Collectors.toList());
        dto.setScenes(sceneDTOs);

        return dto;
    }

    private StreamScheduleDTO.SceneScheduleDTO toSceneDTO(LiveScene scene) {
        StreamScheduleDTO.SceneScheduleDTO dto = new StreamScheduleDTO.SceneScheduleDTO();
        dto.setSceneId(scene.getSceneId().toString());
        dto.setSceneTitle(scene.getSceneTitle());
        dto.setScheduledStartTime(scene.getScheduledStartTime());
        dto.setScheduledEndTime(scene.getScheduledEndTime());
        dto.setDurationSeconds(scene.getDurationSeconds());
        dto.setDayPercentage(scene.getDayPercentage());

        dto.setOriginalStartTime(scene.getOriginalStartTime());
        dto.setOriginalEndTime(scene.getOriginalEndTime());
        dto.setPlaylistRequest(toScenePlaylistRequest(scene));

        List<StreamScheduleDTO.ScheduledSongDTO> songDTOs = scene.getSongs().stream()
                .map(this::toSongDTO)
                .collect(Collectors.toList());
        dto.setSongs(songDTOs);

        List<String> warnings = new ArrayList<>();

        int totalSongDuration = scene.getSongs().stream()
                .mapToInt(PendingSongEntry::getDurationSeconds)
                .sum();

        int sceneDuration = scene.getDurationSeconds();
        int avgIntroDuration = 30;
        int estimatedTotalWithIntros = totalSongDuration + (scene.getSongs().size() * avgIntroDuration);

        if (scene.getSongs().isEmpty()) {
            warnings.add("⚠️ No songs scheduled: Scene will be silent unless DJ fills the entire duration.");
        } else if (estimatedTotalWithIntros > sceneDuration) {
            int overflow = estimatedTotalWithIntros - sceneDuration;
            double overflowMinutes = overflow / 60.0;
            warnings.add(String.format("⚠️ Songs extend beyond scene: Total duration (~%.1f min) exceeds scene duration (%.1f min) by ~%.1f min. Scene will run longer than planned.",
                    estimatedTotalWithIntros / 60.0, sceneDuration / 60.0, overflowMinutes));
        }

        if (!warnings.isEmpty()) {
            dto.setWarning(String.join(" ", warnings));
        }

        return dto;
    }

    private StreamScheduleDTO.ScenePlaylistRequest toScenePlaylistRequest(LiveScene scene) {
        StreamScheduleDTO.ScenePlaylistRequest request = new StreamScheduleDTO.ScenePlaylistRequest();
        request.setSourcing(scene.getSourcing() != null ? scene.getSourcing().name() : null);
        request.setPlaylistTitle(scene.getPlaylistTitle());
        request.setArtist(scene.getArtist());
        request.setGenres(scene.getGenres() != null ? scene.getGenres() : List.of());
        request.setLabels(scene.getLabels() != null ? scene.getLabels() : List.of());
        request.setPlaylistItemTypes(scene.getPlaylistItemTypes() != null
                ? scene.getPlaylistItemTypes().stream().map(Enum::name).collect(Collectors.toList())
                : List.of());
        request.setSourceTypes(scene.getSourceTypes() != null
                ? scene.getSourceTypes().stream().map(Enum::name).collect(Collectors.toList())
                : List.of());
        request.setSearchTerm(scene.getSearchTerm() != null ? scene.getSearchTerm() : "");
        request.setSoundFragments(scene.getSoundFragments() != null ? scene.getSoundFragments() : List.of());
        request.setContentPrompts(scene.getContentPrompts());
        return request;
    }

    private StreamScheduleDTO.ScheduledSongDTO toSongDTO(PendingSongEntry song) {
        StreamScheduleDTO.ScheduledSongDTO dto = new StreamScheduleDTO.ScheduledSongDTO();
        dto.setId(song.getId().toString());
        dto.setSongId(song.getSoundFragment().getId().toString());
        dto.setTitle(song.getSoundFragment().getTitle());
        dto.setArtist(song.getSoundFragment().getArtist());
        dto.setSequenceNumber(song.getSequenceNumber());
        dto.setEstimatedDurationSeconds(song.getDurationSeconds());
        return dto;
    }
}