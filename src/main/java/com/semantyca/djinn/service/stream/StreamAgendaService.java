package com.semantyca.djinn.service.stream;

import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.model.stream.StreamAgenda;
import com.semantyca.djinn.service.BrandService;
import com.semantyca.djinn.service.SceneService;
import com.semantyca.djinn.service.ScriptService;
import com.semantyca.mixpla.model.brand.BrandScriptEntry;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;

@ApplicationScoped
public class StreamAgendaService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamAgendaService.class);
    private static final int AVG_DJ_INTRO_SECONDS = 30;

    @Inject
    BrandService brandService;

    @Inject
    ScriptService scriptService;

    @Inject
    ScheduleSongSupplier scheduleSongSupplier;

    @Inject
    SceneService sceneService;

    public Uni<StreamAgenda> buildRadioStreamAgenda(String slugName, IUser user) {
        return brandService.getBySlugName(slugName)
                .chain(brand -> {
                    if (brand.getScripts() == null || brand.getScripts().isEmpty()) {
                        return Uni.createFrom().failure(
                                new IllegalStateException("Brand has no scripts configured")
                        );
                    }
                    BrandScriptEntry firstScript = brand.getScripts().getFirst();
                    UUID scriptId = firstScript.getScriptId();
                    LOGGER.info("Using first script '{}' for brand '{}'", scriptId, brand.getSlugName());
                    return buildRadioStreamAgenda(brand.getId(), scriptId, user);
                });
    }

    public Uni<StreamAgenda> buildRadioStreamAgenda(UUID brandId, UUID scriptId, IUser user) {
        return brandService.getById(brandId, user)
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
                                                .chain(x -> buildAgenda(script, sourceBrand, scheduleSongSupplier))
                                )
                );
    }

    public Uni<StreamAgenda> buildAgenda(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        LocalDate today = LocalDate.now();
        LocalDateTime scheduleStart = today.atTime(6, 0);
        StreamAgenda schedule = new StreamAgenda(LocalDateTime.now());

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

        LocalDateTime sceneStartTime = scheduleStart;
        List<Uni<LiveScene>> sceneUnis = new ArrayList<>();

        for (int i = 0; i < timeSlots.size(); i++) {
            SceneTimeSlot slot = timeSlots.get(i);
            Scene scene = slot.scene();
            LocalTime sceneOriginalStart = slot.startTime();

            int nextIndex = (i + 1) % timeSlots.size();
            LocalTime sceneOriginalEnd = timeSlots.get(nextIndex).startTime();

            int finalDurationSeconds = calculateDurationUntilNext(sceneOriginalStart, sceneOriginalEnd);

            LocalDateTime finalSceneStartTime = sceneStartTime;

            sceneUnis.add(
                    fetchSongsForSceneWithDuration(sourceBrand, scene, finalDurationSeconds, songSupplier)
                            .map(songs -> {
                                LiveScene entry = new LiveScene(
                                        scene.getId(),
                                        scene.getTitle(),
                                        finalSceneStartTime,
                                        finalDurationSeconds,
                                        sceneOriginalStart,
                                        sceneOriginalEnd,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSourcing() : null,
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
                                LocalDateTime songStartTime = finalSceneStartTime;
                                for (SoundFragment song : songs) {
                                    PendingSongEntry songEntry = new PendingSongEntry(song, songStartTime);
                                    entry.addSong(songEntry);
                                    songStartTime = songStartTime.plusSeconds(songEntry.getDurationSeconds());
                                }
                                return entry;
                            })
            );
            sceneStartTime = sceneStartTime.plusSeconds(finalDurationSeconds);
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    entries.forEach(schedule::addScene);
                    return schedule;
                });
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

        LOGGER.debug("RadioStream scene duration: {}s, effective music time: {}s (talkativity: {}), Selected {} songs with total time: {}s",
                sceneDurationSeconds, effectiveMusicTime, talkativity, selectedSongs.size(), totalTimeUsed);

        return selectedSongs;
    }
}
