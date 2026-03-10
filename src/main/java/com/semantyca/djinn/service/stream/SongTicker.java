package com.semantyca.djinn.service.stream;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.messaging.QueueSupplier;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.service.AiAgentService;
import com.semantyca.djinn.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.AddToQueueDTO;
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
    private static final int MAX_SONGS_PER_BATCH = 2;

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

    @Scheduled(every = "30s")
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
                .limit(MAX_SONGS_PER_BATCH)
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

                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            .chain(agent -> sendSongsWithIntros(brandName, scene, availableSongs, agent, stream, sentSongs));
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

        List<Uni<Void>> sendUnis = new ArrayList<>();

        for (PendingSongEntry songEntry : songs) {
            Uni<Void> sendUni = introTtsGenerator.generateIntroAudioFile(
                            scene,
                            songEntry.getSoundFragment(),
                            agent,
                            stream,
                            broadcastingLanguage
                    )
                    .chain(introFilePath -> {
                        AddToQueueDTO dto = new AddToQueueDTO();
                        dto.setMergingMethod(MergingType.INTRO_SONG);
                        
                        Map<String, String> filePaths = new HashMap<>();
                        filePaths.put("intro", introFilePath);
                        dto.setFilePaths(filePaths);

                        Map<String, UUID> soundFragments = new HashMap<>();
                        soundFragments.put("song", songEntry.getSoundFragment().getId());
                        dto.setSoundFragments(soundFragments);

                        dto.setPriority(100);

                        String uploadId = scene.getSceneId() + ":" + songEntry.getSoundFragment().getId() + ":" + System.currentTimeMillis();

                        return queueSupplier.sendToQueue(brandName, dto, uploadId)
                                .invoke(() -> {
                                    sentSongs.add(songEntry.getSoundFragment().getId());
                                    LOGGER.info("Sent to queue - brand: {}, scene: {}, song: {}, intro: {}", 
                                            brandName, scene.getSceneTitle(), 
                                            songEntry.getSoundFragment().getTitle(), introFilePath);
                                });
                    })
                    .onFailure().invoke(failure -> 
                            LOGGER.error("Failed to send song '{}' for brand: {}, error: {}", 
                                    songEntry.getSoundFragment().getTitle(), brandName, failure.getMessage(), failure)
                    )
                    .onFailure().recoverWithNull();

            sendUnis.add(sendUni);
        }

        return Multi.createFrom().iterable(sendUnis)
                .onItem().transformToUniAndConcatenate(uni -> uni)
                .collect().asList()
                .replaceWithVoid();
    }
}
