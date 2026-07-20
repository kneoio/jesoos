package com.semantyca.jesoos.service.agenda;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.model.stream.*;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.SceneService;
import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.mixpla.model.CustomAction;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingTypeMeta;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Comparator;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

public abstract class AbstractAgendaService {
    private static final Logger LOGGER = Logger.getLogger(AbstractAgendaService.class);

    protected final ScriptService scriptService;
    protected final AiAgentService aiAgentService;
    protected final ScheduleSongSupplier scheduleSongSupplier;
    protected final SceneService sceneService;
    protected final MetricPublisher metricPublisher;
    protected final Random random = new Random();

    protected AbstractAgendaService() {
        this.scriptService = null;
        this.aiAgentService = null;
        this.scheduleSongSupplier = null;
        this.sceneService = null;
        this.metricPublisher = null;
    }

    protected AbstractAgendaService(ScriptService scriptService,
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

    protected static NavigableSet<Scene> orderedSceneSet(List<Scene> list) {
        NavigableSet<Scene> scenes = new TreeSet<>(
                Comparator.comparingInt(Scene::getSeqNum).thenComparing(Scene::getId));
        scenes.addAll(list);
        return scenes;
    }

    protected int calculateDurationUntilNext(java.time.LocalTime start, java.time.LocalTime next) {
        int startSeconds = start.toSecondOfDay();
        int nextSeconds = next.toSecondOfDay();
        if (nextSeconds >= startSeconds) {
            return nextSeconds - startSeconds;
        } else {
            return (24 * 60 * 60 - startSeconds) + nextSeconds;
        }
    }

    protected Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, Set<java.util.UUID> excludeIds) {
        return fetchSongsForSceneWithDuration(scope, scene, maxDurationSeconds, songSupplier, excludeIds, scene.getTalkativity());
    }

    protected Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, double talkativity) {
        return fetchSongsForSceneWithDuration(scope, scene, maxDurationSeconds, songSupplier, Set.of(), talkativity, false);
    }

    protected Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, double talkativity, boolean oneTimeRun) {
        return fetchSongsForSceneWithDuration(scope, scene, maxDurationSeconds, songSupplier, Set.of(), talkativity, oneTimeRun);
    }

    protected Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, Set<java.util.UUID> excludeIds, double talkativity) {
        return fetchSongsForSceneWithDuration(scope, scene, maxDurationSeconds, songSupplier, excludeIds, talkativity, false);
    }

    /**
     * oneTimeRun scenes play the fetched content once at its natural length: no duration-fit
     * loop/repeat, no truncation to maxDurationSeconds.
     */
    protected Uni<SongPool> fetchSongsForSceneWithDuration(SongSourceScope scope, Scene scene, int maxDurationSeconds, ScheduleSongSupplier songSupplier, Set<java.util.UUID> excludeIds, double talkativity, boolean oneTimeRun) {
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();
        WayOfSourcing sourcing = playlistRequest.getSourcing();

        int effectiveDuration = isGeneratedContentScene(playlistRequest)
                ? maxDurationSeconds - MergingTypeMeta.AVERAGE_GENERATED_CONTENT_DURATION_SECONDS
                : maxDurationSeconds;

        Set<java.util.UUID> effectiveExcludes = (excludeIds != null) ? excludeIds : Set.of();
        int songCount = oneTimeRun ? 1 : targetSongCount(effectiveDuration);

        return switch (sourcing) {
            case GENERATED -> Uni.createFrom().item(new SongPool(List.of(), Map.of()));
            case QUERY -> {
                // TODO: shared fragments still cannot be *criteria-matched* — SharedSoundFragmentRepository
                // narrows by type only, not genre/label. They do reach a scene through widenToFill below.
                PlaylistRequest req = new PlaylistRequest();
                req.setSearchTerm(playlistRequest.getSearchTerm());
                req.setGenres(playlistRequest.getGenres());
                req.setLabels(playlistRequest.getLabels());
                req.setType(playlistRequest.getType());
                req.setSource(playlistRequest.getSource());
                yield songSupplier.getSongsByQuery(scope, req, songCount, effectiveExcludes)
                        .chain(matched -> oneTimeRun
                                ? Uni.createFrom().item(new SongPool(matched, Map.of()))
                                : widenToFill(scope, songSupplier, scene, matched, songCount, effectiveExcludes))
                        .map(pool -> new SongPool(oneTimeRun ? pool.songs() : selectDistinctSongsToFillDuration(pool.songs(), effectiveDuration, talkativity), pool.sharedInfo()));
            }
            case STATIC_LIST -> songSupplier.getSongsFromStaticList(scope, playlistRequest.getSoundFragments())
                    .chain(pinned -> oneTimeRun
                            ? Uni.createFrom().item(new SongPool(pinned, Map.of()))
                            : widenToFill(scope, songSupplier, scene, pinned, songCount, effectiveExcludes))
                    .map(pool -> new SongPool(oneTimeRun ? pool.songs() : selectDistinctSongsToFillDuration(pool.songs(), effectiveDuration, talkativity), pool.sharedInfo()));
            default -> songSupplier.getSongsRandomly(scope, PlaylistItemType.SONG, songCount, effectiveExcludes)
                    .map(pool -> new SongPool(oneTimeRun ? pool.songs() : selectDistinctSongsToFillDuration(pool.songs(), effectiveDuration, talkativity), pool.sharedInfo()));
        };
    }

    private static int targetSongCount(int effectiveDuration) {
        return Math.max(10, (int) Math.ceil((double) effectiveDuration / 150));
    }

    /**
     * Ladder rung 2: when a scene's own criteria match fewer songs than the scene needs, top the pool
     * up with any other song rather than let the scene fall back on repeating what it matched.
     * Non-repetition outranks matching the scene's filter. Matched songs stay at the head of the pool,
     * so they are always consumed first and the widening only ever fills what is left over.
     */
    private Uni<SongPool> widenToFill(SongSourceScope scope,
                                      ScheduleSongSupplier songSupplier,
                                      Scene scene,
                                      List<SoundFragment> matched,
                                      int targetCount,
                                      Set<java.util.UUID> excludeIds) {
        if (matched.size() >= targetCount) {
            return Uni.createFrom().item(new SongPool(matched, Map.of()));
        }
        Set<java.util.UUID> alreadyHeld = new java.util.HashSet<>(excludeIds);
        matched.forEach(sf -> alreadyHeld.add(sf.getId()));
        return songSupplier.getAnySongs(scope, targetCount - matched.size(), alreadyHeld)
                .map(widened -> {
                    if (widened.songs().isEmpty()) {
                        return new SongPool(matched, Map.of());
                    }
                    LOGGER.infof("Scene '%s': criteria matched %d of %d songs needed — widened with %d unmatched songs to avoid repeats",
                            scene.getTitle(), matched.size(), targetCount, widened.songs().size());
                    List<SoundFragment> combined = new ArrayList<>(matched);
                    combined.addAll(widened.songs());
                    return new SongPool(combined, widened.sharedInfo());
                });
    }

    /**
     * Fills the scene's budget from the pool along the non-repetition ladder:
     * <ol>
     *   <li>criteria-matched songs, unused — at the head of the pool, so consumed first;</li>
     *   <li>widened songs, unused — appended by {@link #widenToFill};</li>
     *   <li>reuse, never adjacent — only once every song in the pool is spent;</li>
     *   <li>adjacent — unreachable unless the pool holds a single song.</li>
     * </ol>
     * Rungs 1–2 are the whole pool taken at most once each. Rung 3 reuses in pool order, so the
     * least-recently-played song comes back first, and skips any candidate that would land next to
     * itself.
     * <p>
     * Sizing uses the <em>expected</em> per-song overhead rather than a per-song coin flip, so it stays
     * deterministic and cannot disagree with the intro/jingle decisions {@link TimelineBuilder} makes.
     */
    protected List<SoundFragment> selectDistinctSongsToFillDuration(List<SoundFragment> songsPool, int sceneDurationSeconds, double talkativity) {
        if (songsPool.isEmpty()) {
            return songsPool;
        }

        final int expectedOverhead = (int) Math.round(
                talkativity * MergingTypeMeta.AVERAGE_INTRO_DURATION_SECONDS
                        + (1.0 - talkativity) * MergingTypeMeta.AVERAGE_JINGLE_DURATION_SECONDS);

        List<SoundFragment> selectedSongs = new ArrayList<>();
        int totalTimeUsed = 0;

        for (SoundFragment song : songsPool) {
            if (totalTimeUsed >= sceneDurationSeconds) {
                break;
            }
            selectedSongs.add(song);
            totalTimeUsed += songLengthSeconds(song) + expectedOverhead;
        }

        int distinctSongs = selectedSongs.size();

        // Rung 3: every song in the pool is spent and the budget is still open. Reuse rather than leave
        // a gap, but never place a song next to itself. A single-song pool is rung 4 — nothing else exists.
        int reuseIndex = 0;
        while (totalTimeUsed < sceneDurationSeconds) {
            SoundFragment candidate = songsPool.get(reuseIndex % songsPool.size());
            reuseIndex++;
            if (songsPool.size() > 1 && candidate.getId().equals(selectedSongs.getLast().getId())) {
                continue;
            }
            selectedSongs.add(candidate);
            totalTimeUsed += songLengthSeconds(candidate) + expectedOverhead;
        }

        if (selectedSongs.size() > distinctSongs) {
            LOGGER.warnf("Scene needs %ss but the pool holds only %d distinct songs — reused %d non-adjacently to fill it%s",
                    sceneDurationSeconds, distinctSongs, selectedSongs.size() - distinctSongs,
                    songsPool.size() == 1 ? " (single-song pool: adjacency unavoidable)" : "");
        }

        LOGGER.debugf(
                "Scene duration: %ss, talkativity: %.2f, selected %d songs (%d distinct), total used: %ss",
                sceneDurationSeconds, talkativity, selectedSongs.size(), distinctSongs, totalTimeUsed
        );

        return selectedSongs;
    }

    private static int songLengthSeconds(SoundFragment song) {
        return song.getLength() != null ? (int) song.getLength().toSeconds() : 180;
    }

    protected List<SongEntry> convertToSongEntries(List<SoundFragment> soundFragments, Map<java.util.UUID, SongPool.SharedMeta> sharedInfo, int sceneDurationSeconds) {
        List<SongEntry> songEntries = new ArrayList<>();
        for (int i = 0; i < soundFragments.size(); i++) {
            SoundFragment sf = soundFragments.get(i);
            SongPool.SharedMeta meta = sharedInfo != null ? sharedInfo.get(sf.getId()) : null;
            String sharerName = meta != null ? meta.sharerName() : null;
            String contributorEmail = meta != null ? meta.contributorEmail() : null;
            boolean priority = meta != null && meta.priority();
            if (sf.getSource() == SourceType.STREAM) {
                songEntries.add(new SongEntry(sf, new PromptEntry(), i, sharerName, contributorEmail, sceneDurationSeconds, priority));
            } else {
                songEntries.add(new SongEntry(sf, new PromptEntry(), i, sharerName, contributorEmail, priority));
            }
        }
        return songEntries;
    }

    protected void assignPromptsToTimeline(List<TimelineEntry> timeline, List<ScenePrompt> introPrompts, List<CustomAction> actions, AiAgent agent) {
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
}
