package com.semantyca.jesoos.service.agenda;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.*;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.SceneService;
import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.CustomAction;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.ContentStatus;
import com.semantyca.mixpla.model.cnst.MergingTypeMeta;
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
    private final AgendaPersistenceService agendaPersistenceService;
    private final MetricPublisher metricPublisher;
    private final Random random = new Random();

    record SceneTimeSlot(Scene scene, LocalTime startTime) {}

    @Inject
    public AgendaService(ScriptService scriptService,
                         AiAgentService aiAgentService,
                         ScheduleSongSupplier scheduleSongSupplier,
                         SceneService sceneService,
                         AgendaPersistenceService agendaPersistenceService,
                         MetricPublisher metricPublisher) {
        this.scriptService = scriptService;
        this.aiAgentService = aiAgentService;
        this.scheduleSongSupplier = scheduleSongSupplier;
        this.sceneService = sceneService;
        this.agendaPersistenceService = agendaPersistenceService;
        this.metricPublisher = metricPublisher;
    }

    public Uni<StreamAgenda> getStreamAgenda(Brand sourceBrand, IUser user) {
        UUID scriptId = sourceBrand.getScripts().getFirst().getScriptId();
        return getStreamAgenda(sourceBrand, scriptId, user);
    }

    public Uni<StreamAgenda> getStreamAgenda(Brand sourceBrand, UUID scriptId, IUser user) {
        return scriptService.getById(scriptId, user)
                .replaceWith(sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                        .map(AgendaService::orderedSceneSet)
                        .chain(scenes -> buildAgenda(sourceBrand, scenes, scheduleSongSupplier, user)));
    }

    private static NavigableSet<Scene> orderedSceneSet(List<Scene> list) {
        NavigableSet<Scene> scenes = new TreeSet<>(
                Comparator.comparingInt(Scene::getSeqNum).thenComparing(Scene::getId));
        scenes.addAll(list);
        return scenes;
    }

    private Uni<StreamAgenda> buildAgenda(Brand sourceBrand, NavigableSet<Scene> scenes, ScheduleSongSupplier songSupplier, IUser user) {
        ZoneId brandZone = sourceBrand.getTimeZone();
        StreamAgenda schedule = new StreamAgenda(LocalDateTime.now());
        schedule.setTimeZone(brandZone);

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

        Uni<AiAgent> agentUni = (sourceBrand.getAiAgentId() != null)
                ? aiAgentService.getById(sourceBrand.getAiAgentId(), SuperUser.build())
                : Uni.createFrom().nullItem();

        record BuildState(List<LiveScene> liveScenes, Set<UUID> usedIds) {}

        return agentUni.chain(agent -> {
            Uni<BuildState> chain = Uni.createFrom().item(new BuildState(new ArrayList<>(), new HashSet<>()));

            for (int i = 0; i < timeSlots.size(); i++) {
                final Uni<BuildState> prev = chain;
                final SceneTimeSlot slot = timeSlots.get(i);
                final Scene scene = slot.scene();
                final LocalTime sceneOriginalStart = slot.startTime();
                final int nextIndex = (i + 1) % timeSlots.size();
                final int durationSeconds = calculateDurationUntilNext(sceneOriginalStart, timeSlots.get(nextIndex).startTime());

                chain = prev.chain(state ->
                        fetchSongsForSceneWithDuration(sourceBrand, scene, durationSeconds, songSupplier, state.usedIds())
                                .chain(pool -> {
                                    if (pool.songs().isEmpty() && !state.usedIds().isEmpty()) {
                                        LOGGER.infof("Catalog exhausted for scene '%s', resetting exclusion set", scene.getTitle());
                                        state.usedIds().clear();
                                        return fetchSongsForSceneWithDuration(sourceBrand, scene, durationSeconds, songSupplier, state.usedIds());
                                    }
                                    return Uni.createFrom().item(pool);
                                })
                                .map(pool -> {
                                    pool.songs().forEach(sf -> state.usedIds().add(sf.getId()));

                                    LiveScene liveScene = new LiveScene();
                                    liveScene.setSceneId(scene.getId());
                                    liveScene.setSceneTitle(scene.getTitle());
                                    liveScene.setOriginalStartTime(sceneOriginalStart);
                                    liveScene.setTraceId(UUID.randomUUID());
                                    liveScene.setTimeZone(brandZone);
                                    liveScene.setAgentId(sourceBrand.getAiAgentId());
                                    liveScene.setContentStatus(ContentStatus.PENDING);
                                    liveScene.setOneTimeRun(scene.isOneTimeRun());
                                    if (scene.getPlaylistRequest() != null && isGeneratedContentScene(scene.getPlaylistRequest())) {
                                        liveScene.setContentPrompts(scene.getPlaylistRequest().getContentPrompts());
                                    }
                                    liveScene.setIntroPrompts(scene.getIntroPrompts());
                                    liveScene.setActions(scene.getActions());

                                    List<SongEntry> songEntries = convertToSongEntries(pool.songs(), scene.getIntroPrompts(), scene.getActions(), agent, pool.sharerMap());
                                    liveScene.setTimeline(new TimelineBuilder().buildTimeline(
                                            liveScene, songEntries, durationSeconds, scene.getTalkativity(), scene.getIntroPrompts(), scene.getActions()));

                                    state.liveScenes().add(liveScene);
                                    return state;
                                })
                );
            }

            return chain.map(state -> {
                for (LiveScene liveScene : state.liveScenes()) {
                    schedule.addScene(liveScene);
                    if (liveScene.getFitSeconds() > 360) {
                        metricPublisher.publishMetric(
                                sourceBrand.getSlugName(),
                                MetricEventType.WARNING,
                                ProcessType.INDEPENDENT,
                                "scene_content_gap",
                                Map.of(
                                        "scene", liveScene.getSceneTitle(),
                                        "sceneId", liveScene.getSceneId().toString(),
                                        "gapMinutes", TimeFormatUtil.toRoundedMinutes(liveScene.getFitSeconds())
                                )
                        );
                    }
                }
                return schedule;
            });
        }).invoke(agenda -> agendaPersistenceService.persist(agenda, sourceBrand.getId(), user.getId())
                .subscribe().with(
                        id -> LOGGER.debugf("Agenda persisted async for brand %s, config id: %s", sourceBrand.getId(), id),
                        e -> LOGGER.warnf("Agenda persistence failed for brand %s: %s", sourceBrand.getId(), e.getMessage())
                ));
    }

    public Uni<StreamAgenda> buildOtsAgenda(Brand brand, UUID scriptId, LocalDateTime startTime, IUser user) {
        return scriptService.getById(scriptId, user)
                .replaceWith(sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                        .map(AgendaService::orderedSceneSet)
                        .chain(scenes -> buildOtsAgendaFromScenes(brand, startTime, scenes, user)));
    }

    private Uni<StreamAgenda> buildOtsAgendaFromScenes(Brand brand, LocalDateTime startTime, NavigableSet<Scene> scenes, IUser user) {
        ZoneId brandZone = brand.getTimeZone();
        StreamAgenda schedule = new StreamAgenda(startTime);
        schedule.setTimeZone(brandZone);

        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        List<Uni<LiveScene>> sceneUnis = new ArrayList<>();
        LocalDateTime currentTime = startTime;

        for (Scene scene : scenes) {
            int durationSeconds = scene.getDurationSeconds();
            LocalDateTime sceneStart = currentTime;
            currentTime = currentTime.plusSeconds(durationSeconds);

            TimelineBuilder timelineBuilder = new TimelineBuilder();
            Uni<AiAgent> agentUni = (brand.getAiAgentId() != null)
                    ? aiAgentService.getById(brand.getAiAgentId(), SuperUser.build())
                    : Uni.createFrom().nullItem();

            sceneUnis.add(
                    Uni.combine().all().unis(
                                    fetchSongsForSceneWithDuration(brand, scene, durationSeconds, scheduleSongSupplier),
                                    agentUni
                            ).asTuple()
                            .map(tuple -> {
                                SongPool pool = tuple.getItem1();
                                AiAgent agent = tuple.getItem2();

                                LiveScene liveScene = new LiveScene();
                                liveScene.setSceneId(scene.getId());
                                liveScene.setSceneTitle(scene.getTitle());
                                liveScene.setOriginalStartTime(sceneStart.toLocalTime());
                                liveScene.setTraceId(UUID.randomUUID());
                                liveScene.setTimeZone(brandZone);
                                liveScene.setAgentId(brand.getAiAgentId());
                                liveScene.setContentStatus(ContentStatus.PENDING);
                                liveScene.setOneTimeRun(scene.isOneTimeRun());
                                if (scene.getPlaylistRequest() != null
                                        && isGeneratedContentScene(scene.getPlaylistRequest())) {
                                    liveScene.setContentPrompts(scene.getPlaylistRequest().getContentPrompts());
                                }
                                liveScene.setIntroPrompts(scene.getIntroPrompts());
                                liveScene.setActions(scene.getActions());

                                List<SongEntry> songEntries = convertToSongEntries(pool.songs(), scene.getIntroPrompts(), scene.getActions(), agent, pool.sharerMap());
                                List<TimelineEntry> timeline = timelineBuilder.buildTimeline(
                                        liveScene, songEntries, durationSeconds, scene.getTalkativity(), scene.getIntroPrompts(), scene.getActions());
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

    private Uni<SongPool> fetchSongsForSceneWithDuration(Brand brand, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier) {
        return fetchSongsForSceneWithDuration(brand, scene, maxDurationSeconds, songSupplier, Set.of());
    }

    private Uni<SongPool> fetchSongsForSceneWithDuration(Brand brand, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, Set<UUID> excludeIds) {
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();
        WayOfSourcing sourcing = playlistRequest.getSourcing();

        int effectiveDuration = isGeneratedContentScene(playlistRequest)
                ? maxDurationSeconds - MergingTypeMeta.AVERAGE_GENERATED_CONTENT_DURATION_SECONDS
                : maxDurationSeconds;

        return switch (sourcing) {
            case GENERATED -> Uni.createFrom().item(new SongPool(List.of(), Map.of()));
            case QUERY -> {
                PlaylistRequest req = new PlaylistRequest();
                req.setSearchTerm(playlistRequest.getSearchTerm());
                req.setGenres(playlistRequest.getGenres());
                req.setLabels(playlistRequest.getLabels());
                req.setType(playlistRequest.getType());
                req.setSource(playlistRequest.getSource());
                yield songSupplier.getSongsByQuery(brand.getId(), req, maxDurationSeconds)
                        .map(songs -> new SongPool(stripSongsToFitDurationWithTalkativity(songs, effectiveDuration, scene.getTalkativity()), Map.of()));
            }
            case STATIC_LIST -> songSupplier.getSongsFromStaticList(brand.getId(), playlistRequest.getSoundFragments(), maxDurationSeconds)
                    .map(songs -> new SongPool(stripSongsToFitDurationWithTalkativity(songs, effectiveDuration, scene.getTalkativity()), Map.of()));
            default -> songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxDurationSeconds, excludeIds)
                    .map(pool -> new SongPool(stripSongsToFitDurationWithTalkativity(pool.songs(), effectiveDuration, scene.getTalkativity()), pool.sharerMap()));
        };
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
                    totalTimeUsed += timePerSong;
                    break;
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


    private List<SongEntry> convertToSongEntries(List<SoundFragment> soundFragments, List<ScenePrompt> introPrompts, List<CustomAction> actions, AiAgent agent, Map<UUID, String> sharerMap) {
        List<SongEntry> songEntries = new ArrayList<>();

        List<IntroSource> pool = new ArrayList<>();
        if (introPrompts != null) {
            introPrompts.stream().filter(ScenePrompt::isActive)
                    .map(PromptIntroSource::new)
                    .forEach(pool::add);
        }
        if (actions != null) {
            actions.stream().map(ActionIntroSource::new).forEach(pool::add);
        }

        for (int i = 0; i < soundFragments.size(); i++) {
            SoundFragment sf = soundFragments.get(i);
            PromptEntry promptEntry = new PromptEntry();

            if (!pool.isEmpty() && agent != null) {
                IntroSource selected = pool.get(random.nextInt(pool.size()));
                LanguageTag languageTag = AiHelperUtils.selectLanguageByWeight(agent);
                promptEntry.setLanguage(languageTag.toLanguageCode());
                switch (selected) {
                    case PromptIntroSource p -> promptEntry.setPromptId(p.scenePrompt().getPromptId());
                    case ActionIntroSource a -> promptEntry.setCustomAction(a.customAction());
                }
            }

            String sharerName = sharerMap != null ? sharerMap.get(sf.getId()) : null;
            songEntries.add(new SongEntry(sf, promptEntry, i, sharerName));
        }
        return songEntries;
    }

    static boolean isGeneratedContentScene(PlaylistRequest req) {
        return req != null && req.getSourcing() == WayOfSourcing.GENERATED;
    }
}