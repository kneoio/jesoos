package com.semantyca.jesoos.service.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.config.JesoosConfig;
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
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.util.*;

import static com.semantyca.jesoos.util.AiHelperUtils.getIntroKeyByIndex;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class StaggeredSongScheduler {

    private static final Logger LOGGER = Logger.getLogger(StaggeredSongScheduler.class);

    private static final int AIVOX_DELAY = 3; // seconds (empirical tuning)

    @Inject Vertx vertx;
    @Inject BrandPool brandPool;
    @Inject IntroTtsGenerator introTtsGenerator;
    @Inject QueueSupplier queueSupplier;
    @Inject AiAgentService aiAgentService;
    @Inject MixingTypeStrategy mixingTypeStrategy;
    @Inject JinglePlaybackHandler jinglePlaybackHandler;
    @Inject ScenePool scenePool;

    public void scheduleSceneSongs(String brandName, LiveScene scene) {
        if (scene.getSongs().isEmpty()) {
            scenePool.removeScene(brandName);
            return;
        }

        long startTime = System.currentTimeMillis();
        scheduleNextBatch(brandName, scene, 0, startTime, 0);
    }

    private void scheduleNextBatch(String brandName,
                                   LiveScene scene,
                                   int startIndex,
                                   long startTime,
                                   int timelineSeconds) {

        List<PendingSongEntry> allSongs = scene.getSongs();

        if (startIndex >= allSongs.size()) {
            scenePool.removeScene(brandName);
            return;
        }

        List<PendingSongEntry> remaining = allSongs.subList(startIndex, allSongs.size());
        boolean hasIntros = scene.getIntroPrompts().stream().anyMatch(ScenePrompt::isActive);

        MixingTypeStrategy.MixingTypeConfig cfg =
                mixingTypeStrategy.selectStrategy(remaining.size(), hasIntros);

        List<PendingSongEntry> batch = remaining.stream()
                .limit(cfg.batchSize())
                .toList();

        long targetTime = startTime
                + (timelineSeconds * 1000L)
                - (AIVOX_DELAY * 1000L);

        long delay = Math.max(0, targetTime - System.currentTimeMillis());

        vertx.setTimer(delay, id -> {
            sendBatch(brandName, scene, batch, startIndex, cfg)
                    .subscribe().with(
                            v -> {
                                int duration = batch.stream()
                                        .mapToInt(PendingSongEntry::getDurationSeconds)
                                        .sum();

                                scheduleNextBatch(
                                        brandName,
                                        scene,
                                        startIndex + batch.size(),
                                        startTime,
                                        timelineSeconds + duration
                                );
                            },
                            err -> LOGGER.errorf("Batch failed: %s", err.getMessage())
                    );
        });
    }

    private Uni<Void> sendBatch(String brandName,
                                LiveScene scene,
                                List<PendingSongEntry> songs,
                                int startIndex,
                                MixingTypeStrategy.MixingTypeConfig cfg) {

        return brandPool.get(brandName)
                .chain(stream -> {
                    if (AiHelperUtils.shouldPlayJingle(scene.getTalkativity())) {
                        return jinglePlaybackHandler.handleJingleAndSong(stream, scene, startIndex);
                    }

                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            .chain(agent -> sendSongs(brandName, scene, songs, agent, stream, startIndex, cfg));
                });
    }

    private Uni<Void> sendSongs(String brandName,
                                LiveScene scene,
                                List<PendingSongEntry> songs,
                                AiAgent agent,
                                IStream stream,
                                int startIndex,
                                MixingTypeStrategy.MixingTypeConfig cfg) {

        LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);

        List<Uni<IntroTtsGenerator.IntroAudioResult>> introUnis = new ArrayList<>();

        if (cfg.needsIntros()) {
            for (PendingSongEntry s : songs) {
                introUnis.add(introTtsGenerator.generateIntroAudioFile(
                        scene, s.getSoundFragment(), agent, stream, lang));
            }
        } else {
            for (int i = 0; i < songs.size(); i++) {
                introUnis.add(Uni.createFrom().nullItem());
            }
        }

        return Uni.join().all(introUnis).andCollectFailures()
                .chain(intros -> {

                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                    dto.setMergingMethod(cfg.mergingType());
                    dto.setSceneId(scene.getSceneId());
                    dto.setSceneTitle(scene.getSceneTitle());
                    dto.setSequenceNumber(startIndex);
                    dto.setPriority(9);

                    Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                    for (int i = 0; i < songs.size(); i++) {
                        PendingSongEntry s = songs.get(i);

                        IntroTtsGenerator.IntroAudioResult intro = intros.get(i);

                        if (intro != null) {
                            introMap.put(getIntroKeyByIndex(i),
                                    new IntroInfoDTO(intro.filePath(), intro.durationSeconds()));
                        }

                        songMap.put(getSongKeyByIndex(i),
                                new SongInfoDTO(s.getSoundFragment().getId(), s.getDurationSeconds()));
                    }

                    dto.setFilePaths(introMap);
                    dto.setSongs(songMap);

                    long deadline = scene.getScheduledEndTime()
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli();

                    dto.setSceneDeadlineTimestamp(deadline);

                    return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId());
                });
    }
}