package com.semantyca.jesoos.service.agenda;

import com.semantyca.core.model.user.IUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.*;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.SceneService;
import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.ContentStatus;
import com.semantyca.mixpla.model.cnst.SceneType;
import com.semantyca.mixpla.model.cnst.SourceType;
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
public class RadioAgendaService extends AbstractAgendaService {
    private static final Logger LOGGER = Logger.getLogger(RadioAgendaService.class);

    record SceneTimeSlot(Scene scene, LocalTime startTime) {}

    @Inject
    public RadioAgendaService(ScriptService scriptService,
                               AiAgentService aiAgentService,
                               ScheduleSongSupplier scheduleSongSupplier,
                               SceneService sceneService,
                               MetricPublisher metricPublisher) {
        super(scriptService, aiAgentService, scheduleSongSupplier, sceneService, metricPublisher);
    }

    public Uni<StreamAgenda> getStreamAgenda(Brand sourceBrand, IUser user) {
        UUID scriptId = sourceBrand.getScriptIds().getFirst().getScriptId();
        return scriptService.getById(scriptId, user)
                .replaceWith(sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                        .map(AbstractAgendaService::orderedSceneSet)
                        .chain(scenes -> buildAgenda(sourceBrand, scenes, scheduleSongSupplier, user)));
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
            activeScenes.stream()
                    .filter(s -> s.getSceneType() == SceneType.LOOP)
                    .findFirst().ifPresent(baseline -> timeSlots.add(new SceneTimeSlot(baseline, LocalTime.of(0, 0))));
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

    // weekday: ISO 1=Monday .. 7=Sunday; null/empty means active every day.
    private static boolean isActiveOnWeekday(Scene scene, int weekday) {
        List<Integer> days = scene.getWeekdays();
        return days == null || days.isEmpty() || days.contains(weekday);
    }
}
