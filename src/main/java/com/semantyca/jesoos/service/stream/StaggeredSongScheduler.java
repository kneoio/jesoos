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
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.semantyca.jesoos.util.AiHelperUtils.getIntroKeyByIndex;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class StaggeredSongScheduler {

    private static final Logger LOGGER = Logger.getLogger(StaggeredSongScheduler.class);

    private static final int DEADLINE_SAFETY_MARGIN_SEC = 2;

    @Inject Vertx vertx;
    @Inject JesoosConfig jesoosConfig;
    @Inject BrandPool brandPool;
    @Inject IntroTtsGenerator introTtsGenerator;
    @Inject QueueSupplier queueSupplier;
    @Inject AiAgentService aiAgentService;
    @Inject MixingTypeShuffeler mixingTypeShuffeler;
    @Inject JinglePlaybackHandler jinglePlaybackHandler;
    @Inject ScenePool scenePool;
    @Inject MetricPublisher metricPublisher;

    public void scheduleSceneSongs(String brandName, LiveScene scene) {
        if (scene.getSongs().isEmpty()) {
            scenePool.removeScene(brandName);
            return;
        }

        long startTime = System.currentTimeMillis();
        scheduleNextBatch(brandName, scene, 0, startTime, jesoosConfig.getAivoxDelaySeconds());
    }

    private void scheduleNextBatch(String brandName,
                                   LiveScene scene,
                                   int startIndex,
                                   long startTime,
                                   int timelineSeconds) {

        List<PendingSongEntry> allSongs = scene.getSongs();

        long deadline = scene.getScheduledEndTime()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        long now = System.currentTimeMillis();

        long remainingMs = deadline - now;
        long safetyMarginMs = DEADLINE_SAFETY_MARGIN_SEC * 1000L;

       /* if (remainingMs <= safetyMarginMs) {
            scenePool.removeScene(brandName);
            jinglePlaybackHandler.clearScene(scene.getSceneId());
            metricPublisher.publishMetric(
                    brandName,
                    MetricEventType.WARNING,
                    "safety_margin_situation",
                    Map.of(
                            "sceneId", scene.getSceneId().toString(),
                            "safety margin values", remainingMs + "<=" + safetyMarginMs
                            ),
                    scene.getTraceId()
            );
            return;
        }*/

        if (startIndex >= allSongs.size()) {
            scenePool.removeScene(brandName);
            jinglePlaybackHandler.clearScene(scene.getSceneId());
            metricPublisher.publishMetric(
                    brandName,
                    MetricEventType.WARNING,
                    "songs_exhausted",
                    Map.of(
                            "sceneId", scene.getSceneId().toString(),
                            "startIndex is more than songs count", startIndex + ">" + allSongs.size()
                    ),
                    scene.getTraceId()
            );
            return;
        }

        int maxPossibleDurationSec = (int) ((remainingMs - safetyMarginMs) / 1000L);

        List<PendingSongEntry> remaining = allSongs.subList(startIndex, allSongs.size());

        List<PendingSongEntry> filtered = new ArrayList<>();
        int accumulated = 0;

        for (PendingSongEntry s : remaining) {
            if (accumulated + s.getDurationSeconds() > maxPossibleDurationSec) {
                break;
            }
            filtered.add(s);
            accumulated += s.getDurationSeconds();
        }

        if (filtered.isEmpty()) {
            scenePool.removeScene(brandName);
            jinglePlaybackHandler.clearScene(scene.getSceneId());
            return;
        }

        boolean hasIntros = scene.getIntroPrompts().stream().anyMatch(ScenePrompt::isActive);

        MixingTypeShuffeler.MixingStrategy strategy =
                mixingTypeShuffeler.selectStrategy(filtered.size(), hasIntros);

        List<PendingSongEntry> batch = filtered.stream()
                .limit(strategy.batchSize())
                .toList();

        long targetTime = Math.min(
                startTime + (timelineSeconds * 1000L) - (jesoosConfig.getAivoxDelaySeconds() * 1000L),
                deadline
        );

        long delay = Math.max(1, targetTime - System.currentTimeMillis());

        metricPublisher.publishMetric(
                brandName,
                com.semantyca.mixpla.dto.queue.metric.MetricEventType.INFORMATION,
                "next_song_batch_scheduled",
                Map.of(
                        "sceneId", scene.getSceneId().toString(),
                        "startIndex", String.valueOf(startIndex),
                        "targetTime", String.valueOf(targetTime),
                        "targetTimeReadable", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                                .format(java.time.Instant.ofEpochMilli(targetTime)
                                        .atZone(ZoneId.systemDefault())),
                        "delayMs", String.valueOf(delay),
                        "timelineSeconds", String.valueOf(timelineSeconds)
                ),
                scene.getTraceId()
        );

        Runnable task = () -> {
            long now1 = System.currentTimeMillis();

            long deadline1 = scene.getScheduledEndTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();

            if (now1 >= deadline1) {
                scenePool.removeScene(brandName);
                jinglePlaybackHandler.clearScene(scene.getSceneId());
                return;
            }

            sendBatch(brandName, scene, batch, startIndex, strategy)
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
        };

        vertx.setTimer(delay, id -> task.run());
    }

    private Uni<Void> sendBatch(String brandName,
                                LiveScene scene,
                                List<PendingSongEntry> songs,
                                int startIndex,
                                MixingTypeShuffeler.MixingStrategy cfg) {

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
                                MixingTypeShuffeler.MixingStrategy cfg) {

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