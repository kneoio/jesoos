package com.semantyca.jesoos.service.stream;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.model.stream.TimelineEntryStatus;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.Vertx;
import com.semantyca.jesoos.config.JesoosConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.semantyca.jesoos.util.AiHelperUtils.getIntroKeyByIndex;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class StaggeredSongScheduler {
    public static final int DEFAULT_JINGLE_DURATION = 10;

    private final Vertx vertx;
    private final IntroTtsGenerator introTtsGenerator;
    private final QueueSupplier queueSupplier;
    private final AiAgentService aiAgentService;
    private final MetricPublisher metricPublisher;
    private final BrandPool brandPool;
    private final SoundFragmentService soundFragmentService;
    private final DjStateService djStateService;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Long>> brandTimers = new ConcurrentHashMap<>();
    private final JesoosConfig config;

    @Inject
    public StaggeredSongScheduler(Vertx vertx,
                                  BrandPool brandPool,
                                  IntroTtsGenerator introTtsGenerator,
                                  QueueSupplier queueSupplier,
                                  AiAgentService aiAgentService,
                                  MetricPublisher metricPublisher,
                                  SoundFragmentService soundFragmentService,
                                  DjStateService djStateService,
                                  JesoosConfig config) {
        this.brandPool = brandPool;
        this.vertx = vertx;
        this.introTtsGenerator = introTtsGenerator;
        this.queueSupplier = queueSupplier;
        this.aiAgentService = aiAgentService;
        this.metricPublisher = metricPublisher;
        this.soundFragmentService = soundFragmentService;
        this.djStateService = djStateService;
        this.config = config;
    }

    public void scheduleSceneSongs(String brandName, LiveScene scene) {
        LocalDateTime now = LocalDateTime.now(scene.getTimeZone());
        for (TimelineEntry entry : scene.getTimeline()) {
            if (entry.getStatus() != TimelineEntryStatus.PENDING) {
                continue;
            }
            if (entry.getScheduledEmissionTime().isBefore(now)) {
                LocalDateTime entryEnd = entry.getScheduledEmissionTime()
                        .plusSeconds(entry.getEstimatedDurationSeconds());
                if (entryEnd.isBefore(now)) {
                    entry.setStatus(TimelineEntryStatus.SKIPPED);
                    continue;
                }
            }
            scheduleTimelineEntry(brandName, scene, entry, scene.getTimeZone());
        }
    }


    private void scheduleTimelineEntry(String brandName, LiveScene scene, TimelineEntry entry, ZoneId brandZone) {
        if (!entry.compareAndSetStatus(TimelineEntryStatus.PENDING, TimelineEntryStatus.SCHEDULED)) {  //???  why do we need the check ?
            return;
        }

        long emissionTime = entry.getScheduledEmissionTime()
                .atZone(brandZone)
                .toInstant()
                .toEpochMilli();

        long now = System.currentTimeMillis();
        long leadTimeMs = config.getAivoxDelaySeconds() * 1000L;
        long delay = Math.max(1, emissionTime - leadTimeMs - now);

        Runnable task = () -> {
            long deadline = scene.getEndTime()
                    .atZone(brandZone)
                    .toInstant()
                    .toEpochMilli();

            if (System.currentTimeMillis() >= deadline) {
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                return;
            }

            entry.setStatus(TimelineEntryStatus.EMITTING);

            emitTimelineEntry(brandName, scene, entry, brandZone)
                    .subscribe().with(
                            v -> {
                                entry.setStatus(TimelineEntryStatus.COMPLETED);
                                metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.FLOW,"entry_emitted",
                                        Map.of("seq", entry.getSequenceNumber(), "scene", scene.getSceneTitle(),
                                                "strategy", entry.getMixingStrategy().name()),
                                        scene.getTraceId());
                            },
                            err -> {
                                entry.setStatus(TimelineEntryStatus.FAILED);
                                String errorMsg = err.getMessage();
                                if (errorMsg == null) {
                                    errorMsg = err.getClass().getSimpleName();
                                }
                                metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW,"entry_failed",
                                        Map.of("seq", entry.getSequenceNumber(), "scene", scene.getSceneTitle(),
                                                "error", errorMsg),
                                        scene.getTraceId());
                            }
                    );
        };

        long timerId = vertx.setTimer(delay, id -> {
            ConcurrentHashMap<Integer, Long> timers = brandTimers.get(brandName);
            if (timers != null) timers.remove(entry.getSequenceNumber());
            task.run();
        });
        brandTimers.computeIfAbsent(brandName, k -> new ConcurrentHashMap<>())
                .put(entry.getSequenceNumber(), timerId);

    }

    private Uni<Void> emitTimelineEntry(String brandName, LiveScene scene, TimelineEntry entry, ZoneId brandZone) {
        return brandPool.get(brandName)
                .chain(stream -> {
                    if (entry.isHasJingle()) {
                        return sendJingle(brandName, scene, entry, stream, brandZone);
                    }

                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            .chain(agent -> send(brandName, scene, entry, agent, stream, brandZone));
                });
    }

    private Uni<Void> send(String brandName,
                           LiveScene scene,
                           TimelineEntry entry,
                           AiAgent agent,
                           IStream stream,
                           ZoneId brandZone) {

        MergingType mixingStrategy = entry.getMixingStrategy();
        boolean djEnabled = djStateService.isDjEnabled(brandName);
        long sceneDeadlineMs = scene.getEndTime()
                .atZone(brandZone)
                .toInstant()
                .toEpochMilli();

        if (djEnabled){
            LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);
            boolean shouldGenerateIntros = entry.isHasIntro();
            List<Uni<IntroTtsGenerator.IntroAudioResult>> introUnis = new ArrayList<>();
            for (int i = 0; i < entry.getSongs().size(); i++) {
                if (shouldGenerateIntros) {
                    introUnis.add(introTtsGenerator.generateIntroAudioFile(
                            scene, entry.getSongs().get(i), agent, stream, lang));
                } else {
                    introUnis.add(Uni.createFrom().nullItem());
                }
            }

            MergingType finalMixingStrategy = mixingStrategy;
            return Uni.join().all(introUnis).andCollectFailures()
                    .chain(intros -> {
                        SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, finalMixingStrategy, sceneDeadlineMs);

                        Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                        Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                        for (int i = 0; i < entry.getSongs().size(); i++) {
                            IntroTtsGenerator.IntroAudioResult intro = intros.get(i);

                            if (intro != null) {
                                introMap.put(getIntroKeyByIndex(i),
                                        new IntroInfoDTO(intro.filePath(), intro.durationSeconds()));
                            }

                            songMap.put(getSongKeyByIndex(i),
                                    new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                            entry.getSongs().get(i).getDurationSeconds()));
                        }

                        dto.setFilePaths(introMap);
                        dto.setSongs(songMap);

                        return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId());
                    });
        } else {
            MergingType[] availableTypes = getNoIntroMergingTypes(entry);
            mixingStrategy = availableTypes[ThreadLocalRandom.current().nextInt(availableTypes.length)];

            SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, mixingStrategy, sceneDeadlineMs);

            Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
            Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

            for (int i = 0; i < entry.getSongs().size(); i++) {
                songMap.put(getSongKeyByIndex(i),
                        new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                entry.getSongs().get(i).getDurationSeconds()));
            }

            dto.setFilePaths(introMap);
            dto.setSongs(songMap);

            return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId());
        }

    }

    private static SongQueueMessageDTO createBaseSongQueueMessage(LiveScene scene, TimelineEntry entry, MergingType mixingStrategy, long deadline) {
        SongQueueMessageDTO dto = new SongQueueMessageDTO();
        dto.setMergingMethod(mixingStrategy);
        dto.setSceneId(scene.getSceneId());
        dto.setSceneTitle(scene.getSceneTitle());
        dto.setSequenceNumber(entry.getSequenceNumber());
        dto.setPriority(9);
        dto.setSceneDeadlineTimestamp(deadline);
        return dto;
    }

    private static MergingType @NotNull [] getNoIntroMergingTypes(TimelineEntry entry) {
        MergingType[] availableTypes;
        if (entry.getSongs().size() == 2) {
            availableTypes = new MergingType[]{
                    MergingType.SONG_CROSSFADE_SONG,
                    MergingType.NOT_MIXED
            };
        } else {
            availableTypes = new MergingType[]{
                    MergingType.SONG_ONLY
            };
        }
        return availableTypes;
    }


    private Uni<Void> sendJingle(String brandName,
                                 LiveScene scene,
                                 TimelineEntry entry,
                                 IStream stream,
                                 ZoneId brandZone) {
        return soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE, stream.getId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(jingles -> {
                    long sceneDeadlineMs = scene.getEndTime()
                            .atZone(brandZone)
                            .toInstant()
                            .toEpochMilli();

                    MergingType mergingType;
                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                    if (jingles.isEmpty()) {
                        mergingType = MergingType.SONG_ONLY;
                        for (int i = 0; i < entry.getSongs().size(); i++) {
                            songMap.put(getSongKeyByIndex(i),
                                    new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                            entry.getSongs().get(i).getDurationSeconds()));
                        }
                    } else {
                        SoundFragment jingle = jingles.get(ThreadLocalRandom.current().nextInt(jingles.size()));
                        mergingType = MergingType.FILLER_JINGLE;

                        int jingleDuration = DEFAULT_JINGLE_DURATION;
                        if (jingle.getLength() != null) {
                            jingleDuration = (int) jingle.getLength().toSeconds();
                        }

                        songMap.put(getSongKeyByIndex(0),
                                new SongInfoDTO(jingle.getId(), jingleDuration));

                        for (int i = 0; i < entry.getSongs().size(); i++) {
                            songMap.put(getSongKeyByIndex(i + 1),
                                    new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                            entry.getSongs().get(i).getDurationSeconds()));
                        }
                    }

                    SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, mergingType, sceneDeadlineMs);
                    dto.setFilePaths(new HashMap<>());
                    dto.setSongs(songMap);
                    return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId());
                });
    }

    public void cancelBrandTimers(String brandName) {
        ConcurrentHashMap<Integer, Long> timers = brandTimers.remove(brandName);
        if (timers != null) timers.values().forEach(vertx::cancelTimer);
    }

    public Map<String, Map<Integer, Long>> getBrandTimers() {
        Map<String, Map<Integer, Long>> result = new HashMap<>();
        brandTimers.forEach((brand, timers) -> result.put(brand, new HashMap<>(timers)));
        return result;
    }

    public void cancelPending(String brandName, List<Integer> sequenceNumbers) {
        ConcurrentHashMap<Integer, Long> timers = brandTimers.get(brandName);
        if (timers == null) return;
        for (Integer seq : sequenceNumbers) {
            Long timerId = timers.remove(seq);
            if (timerId != null) vertx.cancelTimer(timerId);
        }
    }

    @PreDestroy
    void cleanup() {
        brandTimers.forEach((brand, timers) -> {
            timers.values().forEach(vertx::cancelTimer);
        });
        brandTimers.clear();
    }
}