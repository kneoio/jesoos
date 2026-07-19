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
import com.semantyca.mixpla.model.cnst.SourceType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@ApplicationScoped
public class OtsAgendaService extends AbstractAgendaService {
    private static final Logger LOGGER = Logger.getLogger(OtsAgendaService.class);

    private record BuildState(List<LiveScene> liveScenes, LocalDateTime currentTime, Set<UUID> usedIds) {}

    @Inject
    public OtsAgendaService(ScriptService scriptService,
                             AiAgentService aiAgentService,
                             ScheduleSongSupplier scheduleSongSupplier,
                             SceneService sceneService,
                             MetricPublisher metricPublisher) {
        super(scriptService, aiAgentService, scheduleSongSupplier, sceneService, metricPublisher);
    }

    public Uni<StreamAgenda> buildAgenda(String streamSlug, Brand brand, UUID scriptId, LocalDateTime startTime, IUser user) {
        assert scriptService != null;
        assert sceneService != null;
        return scriptService.getById(scriptId, user)
                .replaceWith(sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                        .map(AbstractAgendaService::orderedSceneSet)
                        .chain(scenes -> buildAgendaFromScenes(streamSlug, brand, startTime, scenes)));
    }

    /**
     * The show must go on: once earlier scenes have used up the catalog, a later scene would otherwise
     * come back with an empty pool and emit nothing. Drop the exclusion set and refetch — a scene that
     * revisits an earlier scene's song still beats a silent one. Mirrors RadioAgendaService.
     * <p>
     * One-time scenes are exempt: they play a single song at its natural length, so a pool shorter than
     * the nominal duration is expected, not a symptom of exhaustion.
     */
    private Uni<SongPool> resetExclusionIfCatalogExhausted(SongSourceScope scope, Scene scene, int durationSeconds,
                                                           double talkativity, boolean oneTimeRun, BuildState state, SongPool pool) {
        if (oneTimeRun || state.usedIds().isEmpty()) {
            return Uni.createFrom().item(pool);
        }
        long estimatedSeconds = pool.songs().stream()
                .mapToLong(sf -> sf.getLength() != null ? sf.getLength().toSeconds() : 180L)
                .sum();
        if (estimatedSeconds >= durationSeconds) {
            return Uni.createFrom().item(pool);
        }
        LOGGER.infof("Catalog insufficient for scene '%s' (estimated=%ds required=%ds), resetting exclusion set",
                scene.getTitle(), estimatedSeconds, durationSeconds);
        state.usedIds().clear();
        return fetchSongsForSceneWithDuration(scope, scene, durationSeconds, scheduleSongSupplier, state.usedIds(), talkativity, oneTimeRun);
    }

    private Uni<StreamAgenda> buildAgendaFromScenes(String streamSlug, Brand brand, LocalDateTime startTime, NavigableSet<Scene> scenes) {
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

        Uni<AiAgent> agentUni;
        if (agentId != null) {
            assert aiAgentService != null;
            agentUni = aiAgentService.getById(agentId);
        } else {
            agentUni = Uni.createFrom().nullItem();
        }

        double otsTalkativity = 1.0;

        // Scenes are built sequentially, not in parallel: a ONE_TIME scene's real content length
        // (not its nominal Scene.durationSeconds) determines when the next scene actually starts.
        Uni<List<LiveScene>> chain = agentUni.chain(agent -> {
            Uni<BuildState> stateChain = Uni.createFrom().item(new BuildState(new ArrayList<>(), startTime, new LinkedHashSet<>()));

            for (Scene scene : scenes) {
                stateChain = stateChain.chain(state -> {
                    boolean oneTimeRun = scene.getSceneType() == SceneType.ONE_TIME;
                    int durationSeconds = scene.getDurationSeconds();
                    LocalDateTime sceneStart = state.currentTime();

                    return fetchSongsForSceneWithDuration(scope, scene, durationSeconds, scheduleSongSupplier, state.usedIds(), otsTalkativity, oneTimeRun)
                            .chain(pool -> resetExclusionIfCatalogExhausted(scope, scene, durationSeconds, otsTalkativity, oneTimeRun, state, pool))
                            .map(pool -> {
                                pool.songs().stream()
                                        .filter(sf -> sf.getSource() != SourceType.STREAM)
                                        .forEach(sf -> state.usedIds().add(sf.getId()));

                                LiveScene liveScene = new LiveScene();
                                liveScene.setSceneId(scene.getId());
                                liveScene.setSceneTitle(scene.getTitle());
                                liveScene.setOriginalStartTime(sceneStart.toLocalTime());
                                liveScene.setTraceId(buildTraceId);
                                liveScene.setTimeZone(zone);
                                liveScene.setAgentId(agentId);
                                liveScene.setContentStatus(ContentStatus.PENDING);
                                liveScene.setOneTimeRun(oneTimeRun);
                                if (scene.getPlaylistRequest() != null
                                        && isGeneratedContentScene(scene.getPlaylistRequest())) {
                                    liveScene.setContentPrompts(scene.getPlaylistRequest().getContentPrompts());
                                    liveScene.setMixingType(scene.getPlaylistRequest().getMixingType());
                                    liveScene.setMixingArtefacts(scene.getPlaylistRequest().getMixingArtefacts());
                                }
                                liveScene.setIntroPrompts(scene.getIntroPrompts());
                                liveScene.setActions(scene.getActions());

                                List<SongEntry> songEntries = convertToSongEntries(pool.songs(), pool.sharedInfo(), durationSeconds);
                                List<TimelineEntry> timeline = new TimelineBuilder().buildOtsTimeline(
                                        liveScene, songEntries, durationSeconds, otsTalkativity, scene.getIntroPrompts(), scene.getActions(), sceneStart);
                                assignPromptsToTimeline(timeline, scene.getIntroPrompts(), scene.getActions(), agent);
                                liveScene.setTimeline(timeline);

                                LocalDateTime nextStart = liveScene.getEndTime() != null ? liveScene.getEndTime() : sceneStart;
                                state.liveScenes().add(liveScene);
                                return new BuildState(state.liveScenes(), nextStart, state.usedIds());
                            });
                });
            }

            return stateChain.map(BuildState::liveScenes);
        });

        return chain
                .map(liveScenes -> {
                    for (LiveScene liveScene : liveScenes) {
                        schedule.addScene(liveScene);
                        if (liveScene.getFitSeconds() > 360) {
                            assert metricPublisher != null;
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
                    assert metricPublisher != null;
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
                .onFailure().invoke(e -> {
                    assert metricPublisher != null;
                    metricPublisher.publishMetric(
                            streamSlug,
                            MetricEventType.ERROR,
                            ProcessType.INDEPENDENT,
                            "agenda_empty_or_failed",
                            Map.of("stream", streamSlug, "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    );
                });
    }
}
