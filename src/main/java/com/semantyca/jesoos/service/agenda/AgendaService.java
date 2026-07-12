package com.semantyca.jesoos.service.agenda;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.IUser;
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
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.cnst.SceneType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;
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
    private final MetricPublisher metricPublisher;
    private final Random random = new Random();

    record SceneTimeSlot(Scene scene, LocalTime startTime) {}

    @Inject
    public AgendaService(ScriptService scriptService,
                         AiAgentService aiAgentService,
                         ScheduleSongSupplier scheduleSongSupplier,
                         SceneService sceneService,
                         MetricPublisher metricPublisher) {
        this.scriptService = scriptService;
        this.aiAgentService = aiAgentService;
        this.scheduleSongSupplier = scheduleSongSupplier;
        this.sceneService = sceneService;
        this.metricPublisher = metricPublisher;
    }

    public Uni<StreamAgenda> getStreamAgenda(Brand sourceBrand, IUser user) {
        UUID scriptId = sourceBrand.getScriptIds().getFirst().getScriptId();
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
        UUID buildTraceId = UUID.randomUUID();
        long buildT0 = System.currentTimeMillis();

        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        int targetWeekday = LocalDate.now(brandZone).getDayOfWeek().getValue();
        List<Scene> activeScenes = scenes.stream()
                .filter(s -> isActiveOnWeekday(s, targetWeekday))
                .toList();
        if (activeScenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        List<SceneTimeSlot> timeSlots = new ArrayList<>();
        for (Scene scene : activeScenes) {
            if (scene.getStartTime() != null && !scene.getStartTime().isEmpty()) {
                for (LocalTime startTime : scene.getStartTime()) {
                    timeSlots.add(new SceneTimeSlot(scene, startTime));
                }
            }
        }

        if (timeSlots.isEmpty()) {
            // No scene declares start times: a LOOP scene becomes the 24h baseline,
            // anchored at the 00:00 day boundary so it covers the whole day.
            Scene baseline = activeScenes.stream()
                    .filter(s -> s.getSceneType() == SceneType.LOOP)
                    .findFirst().orElse(null);
            if (baseline != null) {
                timeSlots.add(new SceneTimeSlot(baseline, LocalTime.of(0, 0)));
            }
        }

        if (timeSlots.isEmpty()) {
            throw new IllegalStateException("Scenes exist but no start times defined");
        }

        timeSlots.sort(Comparator.comparing(SceneTimeSlot::startTime));

        Uni<AiAgent> agentUni = (sourceBrand.getAiAgentId() != null)
                ? aiAgentService.getById(sourceBrand.getAiAgentId())
                : Uni.createFrom().nullItem();

        record BuildState(List<LiveScene> liveScenes, Set<UUID> usedIds) {}
        record ExpandedSlot(Scene scene, LocalTime startTime, int durationSeconds) {}

        List<ExpandedSlot> expandedSlots = new ArrayList<>();
        // Seed with the brand's LOOP scene so one-time gaps are filled even when the
        // loop scene has no start times of its own (it is the baseline, not a slot).
        Scene currentLoopScene = activeScenes.stream()
                .filter(s -> s.getSceneType() == SceneType.LOOP)
                .findFirst().orElse(null);
        for (int i = 0; i < timeSlots.size(); i++) {
            SceneTimeSlot slot = timeSlots.get(i);
            LocalTime nextStart = timeSlots.get((i + 1) % timeSlots.size()).startTime();
            int totalGap = calculateDurationUntilNext(slot.startTime(), nextStart);
            if (slot.scene().getSceneType() == SceneType.LOOP) {
                currentLoopScene = slot.scene();
                expandedSlots.add(new ExpandedSlot(slot.scene(), slot.startTime(), totalGap));
            } else {
                int oneTimeDuration = 60;
                expandedSlots.add(new ExpandedSlot(slot.scene(), slot.startTime(), oneTimeDuration));
                int remainingGap = totalGap - oneTimeDuration;
                if (remainingGap > 0 && currentLoopScene != null) {
                    expandedSlots.add(new ExpandedSlot(currentLoopScene, slot.startTime().plusSeconds(oneTimeDuration), remainingGap));
                }
            }
        }

        return agentUni.chain(agent -> {
            Uni<BuildState> chain = Uni.createFrom().item(new BuildState(new ArrayList<>(), new LinkedHashSet<>()));

            for (ExpandedSlot expandedSlot : expandedSlots) {
                final Uni<BuildState> prev = chain;
                final Scene scene = expandedSlot.scene();
                final LocalTime sceneOriginalStart = expandedSlot.startTime();
                final int durationSeconds = expandedSlot.durationSeconds();

                chain = prev.chain(state -> {
                    long sceneT0 = System.currentTimeMillis();
                    LOGGER.infof("[buildAgenda] START scene='%s' duration=%ds excludeIds=%d", scene.getTitle(), durationSeconds, state.usedIds().size());
                    SongSourceScope brandScope = new SongSourceScope.BrandScope(sourceBrand.getId());
                    return fetchSongsForSceneWithDuration(brandScope, scene, durationSeconds, songSupplier, state.usedIds())
                            .chain(pool -> {
                                long estimatedSeconds = pool.songs().stream()
                                        .mapToLong(sf -> sf.getLength() != null ? sf.getLength().toSeconds() : 180L)
                                        .sum();
                                if (estimatedSeconds < durationSeconds && !state.usedIds().isEmpty()) {
                                    LOGGER.infof("Catalog insufficient for scene '%s' (estimated=%ds required=%ds), resetting exclusion set", scene.getTitle(), estimatedSeconds, durationSeconds);
                                    state.usedIds().clear();
                                    return fetchSongsForSceneWithDuration(brandScope, scene, durationSeconds, songSupplier, state.usedIds());
                                }
                                return Uni.createFrom().item(pool);
                            })
                            .map(pool -> {
                                LOGGER.infof("[buildAgenda] DONE scene='%s' songs=%d elapsed=%dms", scene.getTitle(), pool.songs().size(), System.currentTimeMillis() - sceneT0);
                                pool.songs().stream()
                                        .filter(sf -> sf.getSource() != SourceType.STREAM)
                                        .forEach(sf -> state.usedIds().add(sf.getId()));

                                LiveScene liveScene = new LiveScene();
                                liveScene.setSceneId(scene.getId());
                                liveScene.setSceneTitle(scene.getTitle());
                                liveScene.setOriginalStartTime(sceneOriginalStart);
                                liveScene.setTraceId(buildTraceId);
                                liveScene.setTimeZone(brandZone);
                                liveScene.setAgentId(sourceBrand.getAiAgentId());
                                liveScene.setContentStatus(ContentStatus.PENDING);
                                liveScene.setOneTimeRun(scene.getSceneType() == SceneType.ONE_TIME);
                                if (scene.getPlaylistRequest() != null && isGeneratedContentScene(scene.getPlaylistRequest())) {
                                    liveScene.setContentPrompts(scene.getPlaylistRequest().getContentPrompts());
                                    liveScene.setMixingType(scene.getPlaylistRequest().getMixingType());
                                    liveScene.setMixingArtefacts(scene.getPlaylistRequest().getMixingArtefacts());
                                }
                                liveScene.setIntroPrompts(scene.getIntroPrompts());
                                liveScene.setActions(scene.getActions());

                                List<SongEntry> songEntries = convertToSongEntries(pool.songs(), pool.sharerMap(), durationSeconds);
                                List<TimelineEntry> timeline = new TimelineBuilder().buildTimeline(
                                        liveScene, songEntries, durationSeconds, scene.getTalkativity(), scene.getIntroPrompts(), scene.getActions(), true);
                                assignPromptsToTimeline(timeline, scene.getIntroPrompts(), scene.getActions(), agent);
                                liveScene.setTimeline(timeline);

                                state.liveScenes().add(liveScene);
                                return state;
                            });
                });
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
                long elapsedMs = System.currentTimeMillis() - buildT0;
                metricPublisher.publishMetric(
                        sourceBrand.getSlugName(),
                        MetricEventType.INFORMATION,
                        ProcessType.INDEPENDENT,
                        "agenda_build_completed",
                        Map.of(
                                "elapsedMs", elapsedMs,
                                "elapsedSec", elapsedMs / 1000,
                                "scenes", state.liveScenes().size()
                        )
                );
                return schedule;
            });
        });
    }

    public Uni<StreamAgenda> buildOtsAgenda(String streamSlug, SongSourceScope scope, UUID agentId, ZoneId zone, UUID scriptId, LocalDateTime startTime, IUser user) {
        return scriptService.getById(scriptId, user)
                .replaceWith(sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                        .map(AgendaService::orderedSceneSet)
                        .chain(scenes -> buildOtsAgendaFromScenes(streamSlug, scope, agentId, zone, startTime, scenes, user)));
    }

    private Uni<StreamAgenda> buildOtsAgendaFromScenes(String streamSlug, SongSourceScope scope, UUID agentId, ZoneId zone, LocalDateTime startTime, NavigableSet<Scene> scenes, IUser user) {
        StreamAgenda schedule = new StreamAgenda(startTime);
        schedule.setTimeZone(zone);
        UUID buildTraceId = UUID.randomUUID();
        long buildT0 = System.currentTimeMillis();

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
            Uni<AiAgent> agentUni = (agentId != null)
                    ? aiAgentService.getById(agentId)
                    : Uni.createFrom().nullItem();

            double otsTalkativity = 1.0;
            sceneUnis.add(
                    Uni.combine().all().unis(
                                    fetchSongsForSceneWithDuration(scope, scene, durationSeconds, scheduleSongSupplier, otsTalkativity),
                                    agentUni
                            ).asTuple()
                            .map(tuple -> {
                                SongPool pool = tuple.getItem1();
                                AiAgent agent = tuple.getItem2();

                                LiveScene liveScene = new LiveScene();
                                liveScene.setSceneId(scene.getId());
                                liveScene.setSceneTitle(scene.getTitle());
                                liveScene.setOriginalStartTime(sceneStart.toLocalTime());
                                liveScene.setTraceId(buildTraceId);
                                liveScene.setTimeZone(zone);
                                liveScene.setAgentId(agentId);
                                liveScene.setContentStatus(ContentStatus.PENDING);
                                liveScene.setOneTimeRun(scene.getSceneType() == SceneType.ONE_TIME);
                                if (scene.getPlaylistRequest() != null
                                        && isGeneratedContentScene(scene.getPlaylistRequest())) {
                                    liveScene.setContentPrompts(scene.getPlaylistRequest().getContentPrompts());
                                    liveScene.setMixingType(scene.getPlaylistRequest().getMixingType());
                                    liveScene.setMixingArtefacts(scene.getPlaylistRequest().getMixingArtefacts());
                                }
                                liveScene.setIntroPrompts(scene.getIntroPrompts());
                                liveScene.setActions(scene.getActions());

                                List<SongEntry> songEntries = convertToSongEntries(pool.songs(), pool.sharerMap(), durationSeconds);
                                List<TimelineEntry> timeline = timelineBuilder.buildTimeline(
                                        liveScene, songEntries, durationSeconds, otsTalkativity, scene.getIntroPrompts(), scene.getActions(), false);
                                assignPromptsToTimeline(timeline, scene.getIntroPrompts(), scene.getActions(), agent);
                                liveScene.setTimeline(timeline);
                                return liveScene;
                            })
            );
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(liveScenes -> {
                    for (LiveScene liveScene : liveScenes) {
                        schedule.addScene(liveScene);
                        if (liveScene.getFitSeconds() > 360) {
                            metricPublisher.publishMetric(
                                    streamSlug,
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
                    long elapsedMs = System.currentTimeMillis() - buildT0;
                    metricPublisher.publishMetric(
                            streamSlug,
                            MetricEventType.INFORMATION,
                            ProcessType.INDEPENDENT,
                            "agenda_build_completed",
                            Map.of(
                                    "elapsedMs", elapsedMs,
                                    "elapsedSec", elapsedMs / 1000,
                                    "scenes", liveScenes.size()
                            )
                    );
                    return schedule;
                })
                .onFailure().invoke(e -> metricPublisher.publishMetric(
                        streamSlug,
                        MetricEventType.ERROR,
                        ProcessType.INDEPENDENT,
                        "agenda_empty_or_failed",
                        Map.of("stream", streamSlug, "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                ));
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

    private Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier) {
        return fetchSongsForSceneWithDuration(scope, scene, maxDurationSeconds, songSupplier, Set.of(), scene.getTalkativity());
    }

    private Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, Set<UUID> excludeIds) {
        return fetchSongsForSceneWithDuration(scope, scene, maxDurationSeconds, songSupplier, excludeIds, scene.getTalkativity());
    }

    private Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, double talkativity) {
        return fetchSongsForSceneWithDuration(scope, scene, maxDurationSeconds, songSupplier, Set.of(), talkativity);
    }

    private Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, Set<UUID> excludeIds, double talkativity) {
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();
        WayOfSourcing sourcing = playlistRequest.getSourcing();

        int effectiveDuration = isGeneratedContentScene(playlistRequest)
                ? maxDurationSeconds - MergingTypeMeta.AVERAGE_GENERATED_CONTENT_DURATION_SECONDS
                : maxDurationSeconds;

        return switch (sourcing) {
            case GENERATED -> Uni.createFrom().item(new SongPool(List.of(), Map.of()));
            case QUERY -> {
                // TODO: also include shared sound fragments in QUERY sourcing (currently RANDOM only)
                PlaylistRequest req = new PlaylistRequest();
                req.setSearchTerm(playlistRequest.getSearchTerm());
                req.setGenres(playlistRequest.getGenres());
                req.setLabels(playlistRequest.getLabels());
                req.setType(playlistRequest.getType());
                req.setSource(playlistRequest.getSource());
                int songCount = Math.max(10, (int) Math.ceil((double) effectiveDuration / 150));
                yield songSupplier.getSongsByQuery(scope, req, songCount)
                        .map(songs -> new SongPool(stripSongsToFitDurationWithTalkativity(songs, effectiveDuration, talkativity), Map.of()));
            }
            case STATIC_LIST -> songSupplier.getSongsFromStaticList(scope, playlistRequest.getSoundFragments(), maxDurationSeconds)
                    .map(songs -> new SongPool(stripSongsToFitDurationWithTalkativity(songs, effectiveDuration, talkativity), Map.of()));
            default -> {
                int songCount = Math.max(10, (int) Math.ceil((double) effectiveDuration / 150));
                yield songSupplier.getSongsRandomly(scope, PlaylistItemType.SONG, songCount, excludeIds)
                        .map(pool -> new SongPool(stripSongsToFitDurationWithTalkativity(pool.songs(), effectiveDuration, talkativity), pool.sharerMap()));
            }
        };
    }

    private List<SoundFragment> stripSongsToFitDurationWithTalkativity(List<SoundFragment> songsPool, int sceneDurationSeconds, double talkativity) {
        if (songsPool.isEmpty()) {
            return songsPool;
        }

        final int introSec = MergingTypeMeta.AVERAGE_INTRO_DURATION_SECONDS;
        final int jingleSec = MergingTypeMeta.AVERAGE_JINGLE_DURATION_SECONDS;

        List<SoundFragment> selectedSongs = new ArrayList<>();
        int totalTimeUsed = 0;

        for (SoundFragment song : songsPool) {
            int songDurationSeconds = song.getLength() != null
                    ? (int) song.getLength().toSeconds()
                    : 180;

            boolean hasIntro = random.nextDouble() < talkativity;
            int overhead = hasIntro ? introSec : jingleSec;
            int timePerSong = songDurationSeconds + overhead;

            selectedSongs.add(song);
            totalTimeUsed += timePerSong;

            if (totalTimeUsed >= sceneDurationSeconds) {
                break;
            }
        }

        LOGGER.debugf(
                "Scene duration: %ss, talkativity: %.2f, selected %d songs, total used: %ss",
                sceneDurationSeconds, talkativity, selectedSongs.size(), totalTimeUsed
        );

        return selectedSongs;
    }


    private List<SongEntry> convertToSongEntries(List<SoundFragment> soundFragments, Map<UUID, String> sharerMap, int sceneDurationSeconds) {
        List<SongEntry> songEntries = new ArrayList<>();
        for (int i = 0; i < soundFragments.size(); i++) {
            SoundFragment sf = soundFragments.get(i);
            String sharerName = sharerMap != null ? sharerMap.get(sf.getId()) : null;
            if (sf.getSource() == SourceType.STREAM) {
                songEntries.add(new SongEntry(sf, new PromptEntry(), i, sharerName, sceneDurationSeconds));
            } else {
                songEntries.add(new SongEntry(sf, new PromptEntry(), i, sharerName));
            }
        }
        return songEntries;
    }

    private void assignPromptsToTimeline(List<TimelineEntry> timeline, List<ScenePrompt> introPrompts, List<CustomAction> actions, AiAgent agent) {
        if (agent == null) return;

        List<IntroSource> pool = new ArrayList<>();
        if (introPrompts != null) {
            introPrompts.stream().filter(ScenePrompt::isActive)
                    .map(PromptIntroSource::new)
                    .forEach(pool::add);
        }
        if (actions != null) {
            actions.stream().map(ActionIntroSource::new).forEach(pool::add);
        }
        if (pool.isEmpty()) return;

        for (TimelineEntry entry : timeline) {
            List<SongEntry> songs = entry.getSongs();
            for (int i = 0; i < songs.size(); i++) {
                if (!entry.isHasIntro() || !introAtIndex(entry.getMixingStrategy(), i)) continue;
                PromptEntry promptEntry = songs.get(i).getPromptEntry();
                IntroSource selected = pool.get(random.nextInt(pool.size()));
                LanguageTag languageTag = AiHelperUtils.selectLanguageByWeight(agent);
                promptEntry.setLanguage(languageTag.toLanguageCode());
                switch (selected) {
                    case PromptIntroSource p -> promptEntry.setPromptId(p.scenePrompt().getPromptId());
                    case ActionIntroSource a -> promptEntry.setCustomAction(a.customAction());
                }
            }
        }
    }

    private static boolean introAtIndex(MixingType type, int index) {
        if (type == MixingType.SONG_INTRO_SONG) return index == 1;
        return true;
    }

    static boolean isGeneratedContentScene(PlaylistRequest req) {
        return req != null && req.getSourcing() == WayOfSourcing.GENERATED;
    }

    // weekday: ISO 1=Monday .. 7=Sunday; null/empty means active every day.
    private static boolean isActiveOnWeekday(Scene scene, int weekday) {
        List<Integer> days = scene.getWeekdays();
        return days == null || days.isEmpty() || days.contains(weekday);
    }
}