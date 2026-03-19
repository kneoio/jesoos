package com.semantyca.jesoos.service.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.config.JesoosConfig;
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
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.semantyca.jesoos.util.AiHelperUtils.getIntroKeyByIndex;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class StaggeredSongScheduler {
    private static final Logger LOGGER = Logger.getLogger(StaggeredSongScheduler.class);

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

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

    @Inject
    ScenePool scenePool;

    private final Map<String, Integer> sentSongsCounter = new ConcurrentHashMap<>();

    public void scheduleSceneSongs(String brandName, LiveScene scene) {
        LOGGER.infof("Scheduling staggered song sends for brand: {}, scene: {}, total songs: {}",
                brandName, scene.getSceneTitle(), scene.getSongs().size());

        List<PendingSongEntry> allSongs = scene.getSongs();
        if (allSongs.isEmpty()) {
            LOGGER.warnf("No songs in scene for brand: {}, scene: {}", brandName, scene.getSceneTitle());
            scenePool.removeScene(brandName);
            return;
        }

        sentSongsCounter.put(brandName, 0);
        scheduleNextBatch(brandName, scene, 0, 0);
    }

    private void scheduleNextBatch(String brandName, LiveScene scene, int startIndex, int cumulativeDurationSeconds) {
        List<PendingSongEntry> allSongs = scene.getSongs();

        if (startIndex >= allSongs.size()) {
            LOGGER.infof("All songs scheduled for brand: {}, scene: {}", brandName, scene.getSceneTitle());
            scenePool.removeScene(brandName);
            sentSongsCounter.remove(brandName);
            return;
        }

        List<PendingSongEntry> remainingSongs = allSongs.subList(startIndex, allSongs.size());
        List<ScenePrompt> introPrompts = scene.getIntroPrompts();
        boolean hasIntros = !introPrompts.isEmpty() && introPrompts.stream().anyMatch(ScenePrompt::isActive);

        MixingTypeStrategy.MixingTypeConfig mixingConfig = mixingTypeStrategy.selectStrategy(remainingSongs.size(), hasIntros);
        int batchSize = mixingConfig.batchSize();
        List<PendingSongEntry> batchSongs = remainingSongs.stream().limit(batchSize).toList();

        long delayMillis = Math.max(1, (cumulativeDurationSeconds - config.bufferSeconds())) * 1000L;

        LOGGER.infof("Scheduling batch for brand: {}, scene: {}, batch size: {}, delay: {}s, songs: {}",
                brandName, scene.getSceneTitle(), batchSize, delayMillis / 1000,
                batchSongs.stream().map(s -> s.getSoundFragment().getTitle()).toList());

        vertx.setTimer(delayMillis, timerId -> {
            sendBatch(brandName, scene, batchSongs)
                    .subscribe()
                    .with(
                            success -> {
                                int batchDuration = batchSongs.stream()
                                        .mapToInt(PendingSongEntry::getDurationSeconds)
                                        .sum();
                                scheduleNextBatch(brandName, scene, startIndex + batchSize, cumulativeDurationSeconds + batchDuration);
                            },
                            failure -> LOGGER.errorf("Failed to send batch for brand: {}, scene: {}, error: {}",
                                    brandName, scene.getSceneTitle(), failure.getMessage(), failure)
                    );
        });
    }

    private Uni<Void> sendBatch(String brandName, LiveScene scene, List<PendingSongEntry> songs) {
        return brandPool.get(brandName)
                .chain(stream -> {
                    double talkativity = scene.getTalkativity();
                    boolean shouldPlayJingle = AiHelperUtils.shouldPlayJingle(talkativity);

                    if (shouldPlayJingle) {
                        return jinglePlaybackHandler.handleJingleAndSong(stream, scene, new java.util.HashSet<>());
                    }

                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            .chain(agent -> sendSongsWithIntros(brandName, scene, songs, agent, stream));
                });
    }

    private Uni<Void> sendSongsWithIntros(
            String brandName,
            LiveScene scene,
            List<PendingSongEntry> songs,
            AiAgent agent,
            IStream stream
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

                    int sequenceNumber = sentSongsCounter.merge(brandName, songs.size(), Integer::sum) - songs.size();
                    dto.setSequenceNumber(sequenceNumber);

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
                                LOGGER.infof("Queued {} songs, brand: {}, scene: {}, seq: {}, {}",
                                        songs.size(), brandName, scene.getSceneTitle(), sequenceNumber, mergingType);
                                Map<String, Object> payload = Map.of(
                                        "scene", dto.getSceneTitle(),
                                        "seq", dto.getSequenceNumber(),
                                        "mixing", dto.getMergingMethod(),
                                        "songCount", songs.size()
                                );
                                metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, "songs_aivoxed", payload);
                            });
                })
                .onFailure().invoke(failure ->
                        LOGGER.errorf("Failed to send songs for brand: {}, error: {}",
                                brandName, failure.getMessage(), failure)
                )
                .onFailure().recoverWithNull();
    }
}