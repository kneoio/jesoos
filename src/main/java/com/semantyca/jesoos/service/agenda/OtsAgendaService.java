package com.semantyca.jesoos.service.agenda;

import com.semantyca.core.model.user.IUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.*;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.SceneService;
import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.ContentStatus;
import com.semantyca.mixpla.model.cnst.SceneType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@ApplicationScoped
public class OtsAgendaService extends AbstractAgendaService {

    @Inject
    public OtsAgendaService(ScriptService scriptService,
                             AiAgentService aiAgentService,
                             ScheduleSongSupplier scheduleSongSupplier,
                             SceneService sceneService,
                             MetricPublisher metricPublisher) {
        super(scriptService, aiAgentService, scheduleSongSupplier, sceneService, metricPublisher);
    }

    public Uni<StreamAgenda> buildAgenda(String streamSlug, Brand brand, UUID scriptId, LocalDateTime startTime, IUser user) {
        return scriptService.getById(scriptId, user)
                .replaceWith(sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                        .map(AbstractAgendaService::orderedSceneSet)
                        .chain(scenes -> buildAgendaFromScenes(streamSlug, brand, startTime, scenes, user)));
    }

    private Uni<StreamAgenda> buildAgendaFromScenes(String streamSlug, Brand brand, LocalDateTime startTime, NavigableSet<Scene> scenes, IUser user) {
        ZoneId zone = brand.getTimeZone();
        UUID agentId = brand.getAiAgentId();
        SongSourceScope scope = brand.getId() != null
                ? new SongSourceScope.BrandScope(brand.getId())
                : new SongSourceScope.OwnerScope(brand.getOwner().getUserId());

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
}
