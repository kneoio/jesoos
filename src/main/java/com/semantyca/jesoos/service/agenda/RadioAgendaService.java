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
    record BuildState(List<LiveScene> liveScenes, Set<UUID> usedIds) {}
    record ExpandedSlot(Scene scene, LocalTime startTime, int durationSeconds) {}

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
                    return fetchSongsForRadioScene(brandScope, scene, durationSeconds, songSupplier, state.usedIds())
                            .chain(pool -> {
                                long estimatedSeconds = pool.songs().stream()
                                        .mapToLong(sf -> sf.getLength() != null ? sf.getLength().toSeconds() : 180L)
                                        .sum();
                                // Reuse rung: the brand's whole catalog is exhausted for this build. Dropping the
                                // exclusion set and refetching returns the least-recently-played songs first
                                // (recency ordering), so reuse is stalest-first, not arbitrary.
                                if (estimatedSeconds < durationSeconds && !state.usedIds().isEmpty()) {
                                    LOGGER.infof("Catalog exhausted for scene '%s' (estimated=%ds required=%ds), reusing least-recently-played", scene.getTitle(), estimatedSeconds, durationSeconds);
                                    state.usedIds().clear();
                                    return fetchSongsForRadioScene(brandScope, scene, durationSeconds, songSupplier, state.usedIds());
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

                                List<SongEntry> songEntries = convertToSongEntries(pool.songs(), pool.sharedInfo(), durationSeconds);
                                List<TimelineEntry> timeline = new TimelineBuilder().buildTimeline(
                                        liveScene, songEntries, durationSeconds, scene.getTalkativity(), scene.getIntroPrompts(), scene.getActions());
                                assignPromptsToTimeline(timeline, scene.getIntroPrompts(), scene.getActions(), agent);
                                liveScene.setTimeline(timeline);

                                state.liveScenes().add(liveScene);
                                return state;
                            });
                });
            }

            return chain.map(state -> {
                repositionPastPrioritySongs(state.liveScenes(), brandZone);
                enforceNoAdjacentRepeats(state.liveScenes());
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

    // A fresh build processes scenes in chronological day order, so floatPriorityToFront (per-scene
    // pool ordering) hands a priority song to whichever scene is built first — often an early-morning
    // one that's already in the past by build time, where it would just get SKIPPED. Bumps any such
    // song into the first still-upcoming entry instead, swapping one song for one song so no
    // scheduledEmissionTime shifts. Used by both the nightly cron and station-startup builds.
    private void repositionPastPrioritySongs(List<LiveScene> liveScenes, ZoneId brandZone) {
        LocalDateTime now = LocalDateTime.now(brandZone);
        List<TimelineEntry> allEntries = liveScenes.stream()
                .filter(s -> s.getTimeline() != null)
                .flatMap(s -> s.getTimeline().stream())
                .toList();

        for (TimelineEntry entry : allEntries) {
            if (entry.getScheduledEmissionTime() == null || !entry.getScheduledEmissionTime().isBefore(now)) {
                continue;
            }
            List<SongEntry> songs = entry.getSongs();
            if (songs == null) {
                continue;
            }
            for (int i = 0; i < songs.size(); i++) {
                SongEntry priority = songs.get(i);
                if (!priority.isPriority()) {
                    continue;
                }
                TimelineEntry target = allEntries.stream()
                        .filter(e -> e.getScheduledEmissionTime() != null && !e.getScheduledEmissionTime().isBefore(now))
                        .filter(e -> e.getSongs() != null && !e.getSongs().isEmpty())
                        .findFirst()
                        .orElse(null);
                if (target == null) {
                    continue;
                }
                List<SongEntry> targetSongs = new ArrayList<>(target.getSongs());
                SongEntry bumped = targetSongs.get(0);
                targetSongs.set(0, priority);
                target.setSongs(targetSongs);

                List<SongEntry> pastSongs = new ArrayList<>(songs);
                pastSongs.set(i, bumped);
                entry.setSongs(pastSongs);

                LOGGER.infof("Repositioned priority song '%s' from past entry #%d to upcoming entry #%d",
                        priority.getSoundFragment().getTitle(), entry.getSequenceNumber(), target.getSequenceNumber());
            }
        }
    }

    // R0 — no song may air back-to-back with itself. Per-scene selection already avoids adjacency
    // within a scene's own fill, but scene boundaries (last song of scene N == first of scene N+1),
    // 2-song entries, and priority repositioning can still butt two copies together. This final pass
    // flattens every scene's entries into one on-air song sequence and swaps any offending song with a
    // later distinct one (identity swap, so each entry keeps its slot count). It runs last, after
    // selection, widening, and priority placement, so nothing downstream can reintroduce an adjacency.
    // A single-song catalog makes adjacency physically unavoidable — logged, never faked.
    private void enforceNoAdjacentRepeats(List<LiveScene> liveScenes) {
        List<SongEntry> flat = new ArrayList<>();
        List<TimelineEntry> entryOf = new ArrayList<>();
        List<Integer> posOf = new ArrayList<>();
        for (LiveScene scene : liveScenes) {
            if (scene.getTimeline() == null) continue;
            for (TimelineEntry entry : scene.getTimeline()) {
                List<SongEntry> songs = entry.getSongs();
                if (songs == null) continue;
                for (int i = 0; i < songs.size(); i++) {
                    if (songs.get(i) == null || songs.get(i).getSoundFragment() == null) continue;
                    flat.add(songs.get(i));
                    entryOf.add(entry);
                    posOf.add(i);
                }
            }
        }
        if (flat.size() < 2) return;

        boolean changed = true;
        for (int pass = 0; pass < 3 && changed; pass++) {
            changed = false;
            for (int i = 1; i < flat.size(); i++) {
                UUID prev = flat.get(i - 1).getSoundFragment().getId();
                UUID moving = flat.get(i).getSoundFragment().getId();
                if (!moving.equals(prev)) continue;

                UUID next = (i + 1 < flat.size()) ? flat.get(i + 1).getSoundFragment().getId() : null;
                int donor = -1;
                for (int j = i + 1; j < flat.size(); j++) {
                    UUID cand = flat.get(j).getSoundFragment().getId();
                    if (cand.equals(prev)) continue;
                    if (next != null && cand.equals(next)) continue;
                    UUID donorPrev = flat.get(j - 1).getSoundFragment().getId();
                    UUID donorNext = (j + 1 < flat.size()) ? flat.get(j + 1).getSoundFragment().getId() : null;
                    if (moving.equals(donorPrev) || moving.equals(donorNext)) continue;
                    donor = j;
                    break;
                }
                if (donor == -1) {
                    LOGGER.warnf("adjacency_unavoidable: song '%s' repeats back-to-back and no distinct swap exists",
                            flat.get(i).getSoundFragment().getTitle());
                    continue;
                }
                SongEntry tmp = flat.get(i);
                flat.set(i, flat.get(donor));
                flat.set(donor, tmp);
                changed = true;
            }
        }

        Map<TimelineEntry, SongEntry[]> rebuilt = new java.util.IdentityHashMap<>();
        for (int k = 0; k < flat.size(); k++) {
            TimelineEntry e = entryOf.get(k);
            rebuilt.computeIfAbsent(e, x -> e.getSongs().toArray(new SongEntry[0]))[posOf.get(k)] = flat.get(k);
        }
        for (Map.Entry<TimelineEntry, SongEntry[]> me : rebuilt.entrySet()) {
            me.getKey().setSongs(new ArrayList<>(Arrays.asList(me.getValue())));
        }
    }

    // Drops a priority-contributed song into the next upcoming timeline slot of the brand's
    // already-live agenda, replacing one already-selected song rather than adding to the scene's
    // duration, so no downstream entry's scheduledEmissionTime shifts.
    //
    // SceneTicker sweeps the *entire* active scene's timeline every 15s and flips every entry it
    // reaches from PENDING to SCHEDULED immediately (registering a Vert.x timer however far out its
    // emission time is) — so a running scene has essentially no PENDING entries left within seconds
    // of starting. SCHEDULED only locks the entry's fire time, not its content: the timer's closure
    // reads entry.getSongs() live when it fires, so a SCHEDULED entry is still safe to swap right up
    // until it flips to EMITTING. Only EMITTING/terminal entries are left untouched. Returns false if
    // no such slot exists so the caller can fall back to a full rebuild.
    public boolean replacePrioritySong(ILiveStream stream, SharedSongEntry prioritySong) {
        StreamAgenda agenda = stream.getAgenda();
        if (agenda == null) {
            return false;
        }
        for (LiveScene scene : agenda.getLiveScenes()) {
            if (scene.getTimeline() == null) {
                continue;
            }
            for (TimelineEntry entry : scene.getTimeline()) {
                TimelineEntryStatus status = entry.getStatus();
                if (status != TimelineEntryStatus.PENDING && status != TimelineEntryStatus.SCHEDULED) {
                    continue;
                }
                List<SongEntry> songs = entry.getSongs();
                if (songs == null || songs.isEmpty()) {
                    continue;
                }
                SongEntry original = songs.get(0);
                SongEntry replacement = new SongEntry(prioritySong.soundFragment(), original.getPromptEntry(),
                        original.getSequenceNumber(), prioritySong.sharerName(), prioritySong.sourceUserEmail(),
                        original.getDurationSeconds(), true);
                List<SongEntry> updated = new ArrayList<>(songs);
                updated.set(0, replacement);
                entry.setSongs(updated);
                LOGGER.infof("Replaced song in scene '%s' entry #%d with priority contribution '%s'",
                        scene.getSceneTitle(), entry.getSequenceNumber(), prioritySong.soundFragment().getTitle());
                return true;
            }
        }
        return false;
    }

    // weekday: ISO 1=Monday .. 7=Sunday; null/empty means active every day.
    private static boolean isActiveOnWeekday(Scene scene, int weekday) {
        List<Integer> days = scene.getWeekdays();
        return days == null || days.isEmpty() || days.contains(weekday);
    }
}
