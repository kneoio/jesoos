package com.semantyca.jesoos.service.stream;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PendingSongEntry;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.IntroInfoDTO;
import com.semantyca.mixpla.dto.queue.IntroKey;
import com.semantyca.mixpla.dto.queue.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.SongKey;
import com.semantyca.mixpla.dto.queue.SongQueueMessageDTO;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.stream.IStream;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.semantyca.mixpla.dto.queue.IntroKey.*;
import static com.semantyca.mixpla.dto.queue.SongKey.*;

@ApplicationScoped
public class SongTicker {
    private static final Logger LOGGER = LoggerFactory.getLogger(SongTicker.class);

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
                            success -> LOGGER.info("Successfully processed songs for brand: {}, scene: {}",
                                    brandName, scene.getSceneTitle()),
                            failure -> LOGGER.error("Failed to process songs for brand: {}, scene: {}, error: {}",
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
            LOGGER.info("No more songs to send for brand: {}, scene: {} - removing from pool",
                    brandName, scene.getSceneTitle());
            scenePool.removeScene(brandName);
            sentSongsTracker.remove(brandName);
            return Uni.createFrom().voidItem();
        }

        return brandPool.get(brandName)
                .chain(stream -> {
                    if (stream == null) {
                        LOGGER.warn("Stream not found in BrandPool for: {}", brandName);
                        return Uni.createFrom().voidItem();
                    }

                    List<ScenePrompt> introPrompts = scene.getIntroPrompts();
                    boolean hasIntros = !introPrompts.isEmpty() && introPrompts.stream().anyMatch(ScenePrompt::isActive);

                    MixingTypeStrategy.MixingTypeConfig mixingType = mixingTypeStrategy.selectStrategy(availableSongs.size(), hasIntros);

                    List<PendingSongEntry> songsToSend = availableSongs.stream()
                            .limit(mixingType.batchSize())
                            .toList();


                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            .chain(agent -> sendSongsWithIntros(brandName, scene, songsToSend, agent, stream, sentSongs, mixingType));
                });
    }

    private Uni<Void> sendSongsWithIntros(
            String brandName,
            LiveScene scene,
            List<PendingSongEntry> songs,
            AiAgent agent,
            IStream stream,
            Set<UUID> sentSongs,
            MixingTypeStrategy.MixingTypeConfig mixing
    ) {
        LanguageTag broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);

        MergingType mergingType = mixing.mergingType();
        boolean needsIntros = mixing.needsIntros();

        List<Uni<String>> introUnis = new ArrayList<>();
        if (needsIntros) {
            for (PendingSongEntry songEntry : songs) {
                Uni<String> introUni = introTtsGenerator.generateIntroAudioFile(
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

        return Uni.join().all(introUnis).andCollectFailures().chain(introFilePaths -> {
                    SongQueueMessageDTO dto = new SongQueueMessageDTO();

                    boolean hasIntros = introFilePaths.stream().anyMatch(Objects::nonNull);

                    dto.setMergingMethod(mergingType);
                    dto.setSceneId(scene.getSceneId());
                    dto.setSceneTitle(scene.getSceneTitle());
                    dto.setSequenceNumber(sentSongs.size());

                    Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                    for (int i = 0; i < songs.size() && i < 4; i++) {
                        PendingSongEntry songEntry = songs.get(i);
                        String introPath = introFilePaths.get(i);

                        IntroKey introKey = getIntroKeyByIndex(i);
                        SongKey songKey = getSongKeyByIndex(i);

                        if (introPath != null) {
                            introMap.put(introKey, new IntroInfoDTO(introPath, 0));
                        }
                        songMap.put(songKey, new SongInfoDTO(
                                songEntry.getSoundFragment().getId(),
                                songEntry.getDurationSeconds()
                        ));
                    }

                    dto.setFilePaths(introMap);
                    dto.setSongs(songMap);
                    dto.setPriority(100);

                    return queueSupplier.sendSongsToQueue(brandName, dto)
                            .invoke(() -> {
                                songs.forEach(song -> sentSongs.add(song.getSoundFragment().getId()));
                                LOGGER.info("Queuing {} songs, brand: {}, scene: {}, {}", songs.size(), brandName, scene.getSceneTitle(), mergingType);
                            });
                })
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to send songs for brand: {}, error: {}",
                                brandName, failure.getMessage(), failure)
                )
                .onFailure().recoverWithNull();
    }

    private IntroKey getIntroKeyByIndex(int index) {
        return switch (index) {
            case 0 -> INTRO_1;
            case 1 -> INTRO_2;
            default -> throw new IllegalArgumentException("Unsupported intro index: " + index);
        };
    }

    private SongKey getSongKeyByIndex(int index) {
        return switch (index) {
            case 0 -> SONG_1;
            case 1 -> SONG_2;
            case 2 -> SONG_3;
            default -> throw new IllegalArgumentException("Unsupported song index: " + index);
        };
    }
}