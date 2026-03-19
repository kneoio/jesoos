package com.semantyca.jesoos.service.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.EnvConst;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PendingSongEntry;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.IntroInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.IntroKey;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.stream.IStream;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.semantyca.jesoos.util.AiHelperUtils.getIntroKeyByIndex;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;
import static com.semantyca.mixpla.dto.queue.livestream.IntroKey.INTRO_1;
import static com.semantyca.mixpla.dto.queue.livestream.IntroKey.INTRO_2;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_1;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_2;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_3;

@ApplicationScoped
public class SongTicker {
    private static final Logger LOGGER = Logger.getLogger(SongTicker.class);

    @Inject
    ScenePool scenePool;

    @Inject
    BrandPool brandPool;

    @Inject
    IntroTtsGenerator introTtsGenerator;

    @Inject
    QueueSupplier queueSupplier;

    @Inject
    AiAgentService aiAgentService;

    @Inject
    MixingTypeStrategy mixingTypeStrategy;

    @Inject
    MetricPublisher metricPublisher;

    @Inject
    JinglePlaybackHandler jinglePlaybackHandler;

    private final Map<String, Set<UUID>> sentSongsTracker = new ConcurrentHashMap<>();

    @Scheduled(every = "60s")
    void tick() {
        Map<String, LiveScene> activeScenes = scenePool.getAllActiveScenes();
        if (activeScenes.isEmpty()) {
            return;
        }

        activeScenes.forEach((brandName, scene) -> {
            processSongsForScene(brandName, scene)
                    .subscribe()
                    .with(
                            success -> LOGGER.infof("Successfully processed songs for brand: {}, scene: {}",
                                    brandName, scene.getSceneTitle()),
                            failure -> LOGGER.errorf("Failed to process songs for brand: {}, scene: {}, error: {}",
                                    brandName, scene.getSceneTitle(), failure.getMessage(), failure)
                    );
        });
    }

    private Uni<Void> processSongsForScene(String brandName, LiveScene scene) {
        Set<UUID> sentSongs = sentSongsTracker.computeIfAbsent(brandName, k -> new HashSet<>());

        List<PendingSongEntry> availableSongs = scene.getSongs().stream()
                .filter(song -> !sentSongs.contains(song.getSoundFragment().getId()))
                .sorted((a, b) -> Integer.compare(a.getSequenceNumber(), b.getSequenceNumber()))
                .toList();

        if (availableSongs.isEmpty()) {
            LOGGER.warnf("No more songs to send for brand: {}, scene: {} - removing from pool",
                    brandName, scene.getSceneTitle());
            scenePool.removeScene(brandName);
            sentSongsTracker.remove(brandName);
            metricPublisher.publishMetric(brandName, MetricEventType.WARNING,
                    Map.of("event", "songs_exhausted", "scene", scene.getSceneTitle()));
            return Uni.createFrom().voidItem();
        }

        return brandPool.get(brandName)
                .chain(stream -> {
                    double talkativity = scene.getTalkativity();
                    boolean shouldPlayJingle = AiHelperUtils.shouldPlayJingle(talkativity);
                    if (shouldPlayJingle) {
                        return jinglePlaybackHandler.handleJingleAndSong(stream, scene, sentSongs);
                    }
                    List<ScenePrompt> introPrompts = scene.getIntroPrompts();
                    boolean hasIntros = !introPrompts.isEmpty() && introPrompts.stream().anyMatch(ScenePrompt::isActive);

                    MixingTypeStrategy.MixingTypeConfig mixingType = mixingTypeStrategy.selectStrategy(availableSongs.size(), hasIntros);

                    List<PendingSongEntry> songsToSend = availableSongs.stream()
                            .limit(mixingType.batchSize())
                            .toList();


                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            .chain(agent -> sendSongsWithIntros(brandName, scene, songsToSend, agent, stream, sentSongs));
                });
    }

    private Uni<Void> sendSongsWithIntros(
            String brandName,
            LiveScene scene,
            List<PendingSongEntry> songs,
            AiAgent agent,
            IStream stream,
            Set<UUID> sentSongs
    ) {

        List<ScenePrompt> introPrompts = scene.getIntroPrompts();
        boolean hasIntros = !introPrompts.isEmpty() && introPrompts.stream().anyMatch(ScenePrompt::isActive);
        MixingTypeStrategy.MixingTypeConfig mixing = mixingTypeStrategy.selectStrategy(songs.size(), hasIntros);
        MergingType mergingType = mixing.mergingType();
        boolean needsIntros = mixing.needsIntros();
        LanguageTag broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);


        List<Uni<IntroTtsGenerator.IntroAudioResult>> introUnis = new ArrayList<>();
        if (needsIntros) {
            for (PendingSongEntry songEntry : songs) {
                Uni<IntroTtsGenerator.IntroAudioResult> introUni = introTtsGenerator.generateIntroAudioFile(
                        scene,
                        songEntry.getSoundFragment(),
                        agent,
                        stream,
                        broadcastingLanguage
                );
                introUnis.add(introUni);
            }
        } else {
            for (int i = 0; i < songs.size(); i++) {
                introUnis.add(Uni.createFrom().nullItem());
            }
        }

        return Uni.join().all(introUnis).andCollectFailures().chain(introResults -> {
                    SongQueueMessageDTO dto = new SongQueueMessageDTO();

                    dto.setMergingMethod(mergingType);
                    dto.setSceneId(scene.getSceneId());
                    dto.setSceneTitle(scene.getSceneTitle());
                    dto.setSequenceNumber(sentSongs.size());

                    Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                    for (int i = 0; i < songs.size() && i < 4; i++) {
                        PendingSongEntry songEntry = songs.get(i);
                        IntroTtsGenerator.IntroAudioResult introResult = introResults.get(i);

                        IntroKey introKey = getIntroKeyByIndex(i);
                        SongKey songKey = getSongKeyByIndex(i);

                        if (introResult != null) {
                            introMap.put(introKey, new IntroInfoDTO(
                                    introResult.filePath(),
                                    introResult.durationSeconds()
                            ));
                        }
                        songMap.put(songKey, new SongInfoDTO(
                                songEntry.getSoundFragment().getId(),
                                songEntry.getDurationSeconds()
                        ));
                    }

                    dto.setFilePaths(introMap);
                    dto.setSongs(songMap);
                    dto.setPriority(9);

                    LocalDateTime sceneEndTime = scene.getScheduledEndTime();
                    long sceneDeadlineMillis = sceneEndTime
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli();
                    dto.setSceneDeadlineTimestamp(sceneDeadlineMillis);

                    LOGGER.infof("Scene deadline set: {} ({}), brand: {}, scene: {}",
                            sceneEndTime, sceneDeadlineMillis, brandName, scene.getSceneTitle());

                    return queueSupplier.sendSongsToQueue(brandName, dto)
                            .invoke(() -> {
                                songs.forEach(song -> sentSongs.add(song.getSoundFragment().getId()));
                                LOGGER.infof("Queuing {} songs, brand: {}, scene: {}, {}", songs.size(), brandName, scene.getSceneTitle(), mergingType);
                                Map<String, Object> payload =
                                        Map.of(
                                                "scene", dto.getSceneTitle(),
                                                "seq", dto.getSequenceNumber(),
                                                "mixing", dto.getMergingMethod(),
                                                "event", "songs_queued",
                                                "songCount", songs.size()
                                        );
                                metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, payload);
                            });
                })
                .onFailure().invoke(failure ->
                        LOGGER.errorf("Failed to send songs for brand: {}, error: {}",
                                brandName, failure.getMessage(), failure)
                )
                .onFailure().recoverWithNull();
    }
}
