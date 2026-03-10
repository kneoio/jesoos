package com.semantyca.djinn.service.stream;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.queue.SongInfoDTO;
import com.semantyca.djinn.dto.queue.SongQueueMessageDTO;
import com.semantyca.djinn.messaging.QueueSupplier;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.service.AiAgentService;
import com.semantyca.djinn.util.AiHelperUtils;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.stream.IStream;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, Set<UUID>> sentSongsTracker = new ConcurrentHashMap<>();

    @Scheduled(every = "5m")
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
        
        int batchSize = Math.random() < 0.5 ? 1 : 2;
        
        List<PendingSongEntry> songsToSend = scene.getSongs().stream()
                .filter(song -> !sentSongs.contains(song.getSoundFragment().getId()))
                .sorted((a, b) -> Integer.compare(a.getSequenceNumber(), b.getSequenceNumber()))
                .limit(batchSize)
                .toList();

        if (songsToSend.isEmpty()) {
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
        LanguageTag broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);

        List<Uni<String>> introUnis = new ArrayList<>();
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

        return Multi.createFrom().iterable(introUnis)
                .onItem().transformToUniAndConcatenate(uni -> uni)
                .collect().asList()
                .chain(introFilePaths -> {
                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                    
                    MergingType mergingType = selectMergingType(songs.size());
                    dto.setMergingMethod(mergingType);
                    dto.setSceneId(scene.getSceneId());
                    dto.setSceneTitle(scene.getSceneTitle());
                    
                    Map<String, String> filePaths = new HashMap<>();
                    Map<String, SongInfoDTO> songMap = new HashMap<>();
                    
                    for (int i = 0; i < songs.size(); i++) {
                        PendingSongEntry songEntry = songs.get(i);
                        String introPath = introFilePaths.get(i);
                        
                        String introKey = "intro" + (i + 1);
                        String songKey = "song" + (i + 1);
                        
                        filePaths.put(introKey, introPath);
                        
                        SongInfoDTO songInfoDTO = new SongInfoDTO(
                            songEntry.getSoundFragment().getId(),
                            songEntry.getSequenceNumber(),
                            songEntry.getDurationSeconds()
                        );
                        songMap.put(songKey, songInfoDTO);
                    }
                    
                    dto.setFilePaths(filePaths);
                    dto.setSongs(songMap);
                    dto.setPriority(100);

                    String uploadId = scene.getSceneId() + ":" + System.currentTimeMillis();

                    return queueSupplier.sendSongsToQueue(brandName, dto, uploadId)
                            .invoke(() -> {
                                songs.forEach(song -> sentSongs.add(song.getSoundFragment().getId()));
                                LOGGER.info("Sent {} songs to queue - brand: {}, scene: {}, mergingType: {}", 
                                        songs.size(), brandName, scene.getSceneTitle(), mergingType);
                            });
                })
                .onFailure().invoke(failure -> 
                        LOGGER.error("Failed to send songs for brand: {}, error: {}", 
                                brandName, failure.getMessage(), failure)
                )
                .onFailure().recoverWithNull();
    }

    private MergingType selectMergingType(int songCount) {
        if (songCount == 1) {
            return Math.random() < 0.5 ? MergingType.INTRO_SONG : MergingType.SONG_ONLY;
        } else {
            double rand = Math.random();
            if (rand < 0.33) {
                return MergingType.INTRO_SONG_INTRO_SONG;
            } else if (rand < 0.66) {
                return MergingType.SONG_CROSSFADE_SONG;
            } else {
                return MergingType.SONG_INTRO_SONG;
            }
        }
    }
}
