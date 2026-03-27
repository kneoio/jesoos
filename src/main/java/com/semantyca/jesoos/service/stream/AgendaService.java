package com.semantyca.jesoos.service.stream;

import com.semantyca.core.model.user.IUser;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.SceneService;
import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@ApplicationScoped
public class AgendaService {
    private static final Logger LOGGER = Logger.getLogger(AgendaService.class);
    private static final int AVG_DJ_INTRO_SECONDS = 30;

    private final ScriptService scriptService;
    private final  ScheduleSongSupplier scheduleSongSupplier;
    private final SceneService sceneService;
    private final TimelineBuilder timelineBuilder;

    record SceneTimeSlot(Scene scene, LocalTime startTime) {}

    @Inject
    public AgendaService(ScriptService scriptService, ScheduleSongSupplier scheduleSongSupplier, 
                        SceneService sceneService, TimelineBuilder timelineBuilder) {
        this.scriptService = scriptService;
        this.scheduleSongSupplier = scheduleSongSupplier;
        this.sceneService = sceneService;
        this.timelineBuilder = timelineBuilder;
    }

    public Uni<StreamAgenda> getStreamAgenda(Brand sourceBrand, IUser user) {
        UUID scriptId = sourceBrand.getScripts().getFirst().getScriptId();
        return scriptService.getById(scriptId, user)
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
                );
    }

    private Uni<StreamAgenda> buildAgenda(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        ZoneId brandZone = sourceBrand.getTimeZone();
        LocalDateTime brandNow = LocalDateTime.now(brandZone);
        StreamAgenda schedule = new StreamAgenda(LocalDateTime.now());
        schedule.setTimeZone(sourceBrand.getTimeZone());

        NavigableSet<Scene> scenes = script.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

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
        LocalDateTime broadcastDayStart = brandNow.isBefore(todayAt6) ? todayAt6.minusDays(1) : todayAt6;
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
                                UUID traceId = UUID.randomUUID();
                                LiveScene liveScene = new LiveScene(
                                        scene.getId(),
                                        scene.getTitle(),
                                        capturedSceneStartTime,
                                        finalDurationSeconds,
                                        sceneOriginalStart,
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
                                liveScene.setTraceId(traceId);
                                liveScene.setTimeZone(brandZone);
                                liveScene.setAgentId(sourceBrand.getAiAgentId());
                                LOGGER.infof("Created LiveScene- brand: %s, scene: %s, traceId: %s, start: %s",
                                        sourceBrand.getSlugName(), scene.getTitle(), traceId, capturedSceneStartTime);
                                int sequenceNumber = 0;
                                for (SoundFragment song : songs) {
                                    SongEntry songEntry = new SongEntry(song, sequenceNumber++);
                                    liveScene.addSong(songEntry);
                                }
                                return liveScene;
                            })
            );
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    for (LiveScene entry : entries) {
                        List<TimelineEntry> timeline = timelineBuilder.buildTimeline(entry);
                        entry.setTimeline(timeline);
                        schedule.addScene(entry);
                    }
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
        Set<UUID> addedSongIds = new HashSet<>();
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
}