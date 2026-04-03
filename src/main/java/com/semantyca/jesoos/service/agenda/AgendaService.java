package com.semantyca.jesoos.service.agenda;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.model.stream.*;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.SceneService;
import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.jesoos.model.cnst.MergingTypeMeta;
import com.semantyca.mixpla.model.cnst.GeneratedContentStatus;
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
import java.util.*;

@ApplicationScoped
public class AgendaService {
    private static final Logger LOGGER = Logger.getLogger(AgendaService.class);

    private final ScriptService scriptService;
    private final AiAgentService aiAgentService;
    private final ScheduleSongSupplier scheduleSongSupplier;
    private final SceneService sceneService;
    private final Random random = new Random();

    record SceneTimeSlot(Scene scene, LocalTime startTime) {}

    @Inject
    public AgendaService(ScriptService scriptService,
                         AiAgentService aiAgentService,
                         ScheduleSongSupplier scheduleSongSupplier,
                         SceneService sceneService) {
        this.scriptService = scriptService;
        this.aiAgentService = aiAgentService;
        this.scheduleSongSupplier = scheduleSongSupplier;
        this.sceneService = sceneService;
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
        StreamAgenda schedule = new StreamAgenda(LocalDateTime.now());
        schedule.setTimeZone(brandZone);

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

        if (timeSlots.isEmpty()) {
            throw new IllegalStateException("Scenes exist but no start times defined");
        }

        timeSlots.sort(Comparator.comparing(SceneTimeSlot::startTime));

        LocalTime dayStart = LocalTime.of(6, 0);
        int shiftIndex = -1;
        for (int i = 0; i < timeSlots.size(); i++) {
            if (!timeSlots.get(i).startTime().isBefore(dayStart)) {
                shiftIndex = i;
                break;
            }
        }

        if (shiftIndex > 0) {
            Collections.rotate(timeSlots, -shiftIndex);
        }

        List<Uni<LiveScene>> sceneUnis = new ArrayList<>();

        for (int i = 0; i < timeSlots.size(); i++) {
            SceneTimeSlot slot = timeSlots.get(i);
            Scene scene = slot.scene();
            LocalTime sceneOriginalStart = slot.startTime();

            int nextIndex = (i + 1) % timeSlots.size();
            LocalTime sceneOriginalEnd = timeSlots.get(nextIndex).startTime();
            int durationSeconds = calculateDurationUntilNext(sceneOriginalStart, sceneOriginalEnd);

            TimelineBuilder timelineBuilder = new TimelineBuilder();

            Uni<AiAgent> agentUni = (sourceBrand.getAiAgentId() != null)
                    ? aiAgentService.getById(sourceBrand.getAiAgentId(), SuperUser.build(), null)
                    : Uni.createFrom().nullItem();

            sceneUnis.add(
                    Uni.combine().all().unis(
                                    fetchSongsForSceneWithDuration(sourceBrand, scene, durationSeconds, songSupplier),
                                    agentUni
                            ).asTuple()
                            .map(tuple -> {
                                List<SoundFragment> soundFragments = tuple.getItem1();
                                AiAgent agent = tuple.getItem2();
                                UUID traceId = UUID.randomUUID();

                                LiveScene liveScene = new LiveScene();
                                liveScene.setSceneId(scene.getId());
                                liveScene.setSceneTitle(scene.getTitle());
                                liveScene.setOriginalStartTime(sceneOriginalStart);
                                liveScene.setTraceId(traceId);
                                liveScene.setTimeZone(brandZone);
                                liveScene.setAgentId(sourceBrand.getAiAgentId());
                                liveScene.setGeneratedContentStatus(GeneratedContentStatus.PENDING);
                                liveScene.setOneTimeRun(scene.isOneTimeRun());
                                if (scene.getPlaylistRequest() != null) {
                                    liveScene.setContentPrompts(scene.getPlaylistRequest().getContentPrompts());
                                }

                                List<SongEntry> songEntries = convertToSongEntries(soundFragments, scene.getIntroPrompts(), agent);

                                List<TimelineEntry> timeline = timelineBuilder.buildTimeline(
                                        liveScene,
                                        songEntries,
                                        durationSeconds,
                                        scene.getTalkativity(),
                                        scene.getIntroPrompts()
                                );

                                liveScene.setTimeline(timeline);
                                return liveScene;
                            })
            );
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(liveScenes -> {
                    for (LiveScene liveScene : liveScenes) {
                        schedule.addScene(liveScene);
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

    private Uni<List<SoundFragment>> fetchSongsForSceneWithDuration(Brand brand, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier) {
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();
        WayOfSourcing sourcing = playlistRequest.getSourcing();

        Uni<List<SoundFragment>> songsPoolUni = switch (sourcing) {
            case QUERY -> {
                PlaylistRequest req = new PlaylistRequest();
                req.setSearchTerm(playlistRequest.getSearchTerm());
                req.setGenres(playlistRequest.getGenres());
                req.setLabels(playlistRequest.getLabels());
                req.setType(playlistRequest.getType());
                req.setSource(playlistRequest.getSource());
                yield songSupplier.getSongsByQuery(brand.getId(), req, maxDurationSeconds);
            }
            case STATIC_LIST ->
                    songSupplier.getSongsFromStaticList(playlistRequest.getSoundFragments(), maxDurationSeconds);
            default ->
                    songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxDurationSeconds);
        };

        return songsPoolUni.map(songsPool ->
                stripSongsToFitDurationWithTalkativity(songsPool, maxDurationSeconds, scene.getTalkativity()));
    }

    private List<SoundFragment> stripSongsToFitDurationWithTalkativity(List<SoundFragment> songsPool, int sceneDurationSeconds, double talkativity) {
        if (songsPool.isEmpty()) {
            return songsPool;
        }

        final int MAX_PASSES = 2;
        final int introSec = MergingTypeMeta.AVERAGE_INTRO_DURATION_SECONDS;
        final int jingleSec = MergingTypeMeta.AVERAGE_JINGLE_DURATION_SECONDS;

        List<SoundFragment> selectedSongs = new ArrayList<>();
        int totalTimeUsed = 0;
        int pass = 0;

        outer:
        while (totalTimeUsed < sceneDurationSeconds && pass < MAX_PASSES) {
            boolean addedAny = false;

            for (SoundFragment song : songsPool) {
                int songDurationSeconds = song.getLength() != null
                        ? (int) song.getLength().toSeconds()
                        : 180;

                boolean hasIntro = random.nextDouble() < talkativity;
                int overhead = hasIntro ? introSec : jingleSec;

                int timePerSong = songDurationSeconds + overhead;

                if (totalTimeUsed + timePerSong <= sceneDurationSeconds) {
                    selectedSongs.add(song);
                    totalTimeUsed += timePerSong;
                    addedAny = true;
                } else {
                    selectedSongs.add(song);
                    break outer;
                }
            }

            pass++;
            if (!addedAny) break;
        }

        if (totalTimeUsed < sceneDurationSeconds) {
            LOGGER.warnf(
                    "Too few songs to fill scene duration: pool has %d songs covering %ss, scene needs %ss",
                    songsPool.size(), totalTimeUsed, sceneDurationSeconds
            );
        }

        LOGGER.debugf(
                "Scene duration: %ss, talkativity: %.2f, selected %d songs, total used: %ss",
                sceneDurationSeconds, talkativity, selectedSongs.size(), totalTimeUsed
        );

        return selectedSongs;
    }


    private List<SongEntry> convertToSongEntries(List<SoundFragment> soundFragments, List<ScenePrompt> introPrompts, AiAgent agent) {
        List<SongEntry> songEntries = new ArrayList<>();

        List<ScenePrompt> activePrompts = (introPrompts != null)
                ? introPrompts.stream().filter(ScenePrompt::isActive).toList()
                : new ArrayList<>();

        for (int i = 0; i < soundFragments.size(); i++) {
            PromptEntry promptEntry = new PromptEntry();

            if (!activePrompts.isEmpty() && agent != null) {
                ScenePrompt selectedScenePrompt = activePrompts.get(random.nextInt(activePrompts.size()));
                LanguageTag languageTag = AiHelperUtils.selectLanguageByWeight(agent);
                promptEntry.setPromptId(selectedScenePrompt.getPromptId());
                promptEntry.setLanguage(languageTag.toLanguageCode());
            }

            songEntries.add(new SongEntry(soundFragments.get(i), promptEntry, i));
        }
        return songEntries;
    }
}