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
        if (nextSeconds > startSeconds) {
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
                int songCount = oneTimeRun ? 1 : Math.max(10, (int) Math.ceil((double) effectiveDuration / 150));
                yield songSupplier.getSongsByQuery(scope, req, songCount)
                        .map(songs -> new SongPool(oneTimeRun ? songs : stripSongsToFitDurationWithTalkativity(songs, effectiveDuration, talkativity), Map.of()));
            }
            case STATIC_LIST -> songSupplier.getSongsFromStaticList(scope, playlistRequest.getSoundFragments(), maxDurationSeconds)
                    .map(songs -> new SongPool(oneTimeRun ? songs : stripSongsToFitDurationWithTalkativity(songs, effectiveDuration, talkativity), Map.of()));
            default -> {
                int songCount = oneTimeRun ? 1 : Math.max(10, (int) Math.ceil((double) effectiveDuration / 150));
                yield songSupplier.getSongsRandomly(scope, PlaylistItemType.SONG, songCount, excludeIds)
                        .map(pool -> new SongPool(oneTimeRun ? pool.songs() : stripSongsToFitDurationWithTalkativity(pool.songs(), effectiveDuration, talkativity), pool.sharerMap()));
            }
        };
    }

    protected List<SoundFragment> stripSongsToFitDurationWithTalkativity(List<SoundFragment> songsPool, int sceneDurationSeconds, double talkativity) {
        if (songsPool.isEmpty()) {
            return songsPool;
        }

        final int introSec = MergingTypeMeta.AVERAGE_INTRO_DURATION_SECONDS;
        final int jingleSec = MergingTypeMeta.AVERAGE_JINGLE_DURATION_SECONDS;

        List<SoundFragment> selectedSongs = new ArrayList<>();
        int totalTimeUsed = 0;
        int index = 0;

        while (totalTimeUsed < sceneDurationSeconds) {
            SoundFragment song = songsPool.get(index % songsPool.size());
            int songDurationSeconds = song.getLength() != null
                    ? (int) song.getLength().toSeconds()
                    : 180;

            boolean hasIntro = random.nextDouble() < talkativity;
            int overhead = hasIntro ? introSec : jingleSec;
            int timePerSong = songDurationSeconds + overhead;

            selectedSongs.add(song);
            totalTimeUsed += timePerSong;
            index++;
        }

        LOGGER.debugf(
                "Scene duration: %ss, talkativity: %.2f, selected %d songs, total used: %ss",
                sceneDurationSeconds, talkativity, selectedSongs.size(), totalTimeUsed
        );

        return selectedSongs;
    }

    protected List<SongEntry> convertToSongEntries(List<SoundFragment> soundFragments, Map<java.util.UUID, String> sharerMap, int sceneDurationSeconds) {
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
