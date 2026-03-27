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
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private static final Logger LOGGER = Logger.getLogger(StaggeredSongScheduler.class);
    private final Vertx vertx;
    private final IntroTtsGenerator introTtsGenerator;
    private final QueueSupplier queueSupplier;
    private final AiAgentService aiAgentService;
    private final MetricPublisher metricPublisher;
    private final BrandPool brandPool;
    private final SoundFragmentService soundFragmentService;
    private final DjStateService djStateService;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Long>> brandTimers = new ConcurrentHashMap<>();

    @Inject
    public StaggeredSongScheduler(Vertx vertx,
                                  BrandPool brandPool,
                                  IntroTtsGenerator introTtsGenerator,
                                  QueueSupplier queueSupplier,
                                  AiAgentService aiAgentService,
                                  MetricPublisher metricPublisher,
                                  SoundFragmentService soundFragmentService,
                                  DjStateService djStateService) {
        this.brandPool = brandPool;
        this.vertx = vertx;
        this.introTtsGenerator = introTtsGenerator;
        this.queueSupplier = queueSupplier;
        this.aiAgentService = aiAgentService;
        this.metricPublisher = metricPublisher;
        this.soundFragmentService = soundFragmentService;
        this.djStateService = djStateService;
    }

    public void scheduleSceneSongs(String brandName, LiveScene scene) {
        List<TimelineEntry> timeline = scene.getTimeline();
        LOGGER.infof("Scheduling %d timeline entries for scene '%s' (brand: %s)",
                timeline.size(), scene.getSceneTitle(), brandName);



        LocalDateTime now = LocalDateTime.now(scene.getTimeZone());
        int scheduledEntries = 0;
        int skippedEntries = 0;
        int skippedSongsCount = 0;
        int skippedDurationSeconds = 0;

        for (TimelineEntry entry : timeline) {
            if (entry.getStatus() != TimelineEntryStatus.PENDING) {
                continue;
            }
            if (entry.getScheduledEmissionTime().isBefore(now)) {
                LOGGER.debugf("Skipping entry %d for scene '%s' - emission time %s already passed (now: %s)",
                        entry.getSequenceNumber(), scene.getSceneTitle(),
                        entry.getScheduledEmissionTime(), now);
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                skippedEntries++;
                skippedSongsCount += entry.getSongs().size();
                skippedDurationSeconds += entry.getEstimatedDurationSeconds();
                continue;
            }

            scheduleTimelineEntry(brandName, scene, entry, scene.getTimeZone());
            scheduledEntries++;
        }

        LOGGER.infof("Scene '%s': scheduled %d entries, skipped %d entries (%d songs, %d seconds)",
                scene.getSceneTitle(), scheduledEntries, skippedEntries, skippedSongsCount, skippedDurationSeconds);

        metricPublisher.publishMetric(
                brandName,
                MetricEventType.INFORMATION,
                "timeline_scheduled",
                Map.of(
                        "sceneId", scene.getSceneId().toString(),
                        "totalEntries", timeline.size(),
                        "scheduledEntries", scheduledEntries,
                        "skippedEntries", skippedEntries,
                        "skippedSongsCount", skippedSongsCount,
                        "skippedDurationSeconds", skippedDurationSeconds,
                        "firstEmission", timeline.getFirst().getScheduledEmissionTime().toString(),
                        "lastEmission", timeline.getLast().getScheduledEmissionTime().toString()
                ),
                scene.getTraceId()
        );
    }


    private void scheduleTimelineEntry(String brandName, LiveScene scene, TimelineEntry entry, ZoneId brandZone) {
        if (!entry.compareAndSetStatus(TimelineEntryStatus.PENDING, TimelineEntryStatus.SCHEDULED)) {
            LOGGER.infof("Entry %d for scene '%s' already scheduled, ignoring duplicate request",
                    entry.getSequenceNumber(), scene.getSceneTitle());
            return;
        }

        long emissionTime = entry.getScheduledEmissionTime()
                .atZone(brandZone)
                .toInstant()
                .toEpochMilli();

        long now = System.currentTimeMillis();
        long delay = Math.max(1, emissionTime - now);

        String formattedEmissionTime = entry.getScheduledEmissionTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        LOGGER.infof("Scheduling entry %d for scene '%s' (brand: %s): %d songs at %s (delay: %dms / %.1f minutes)",
                entry.getSequenceNumber(), scene.getSceneTitle(), brandName, entry.getSongs().size(),
                formattedEmissionTime, delay, delay / 60000.0);

        entry.setStatus(TimelineEntryStatus.SCHEDULED);

        Runnable task = () -> {
            LOGGER.infof("Timer fired for entry %d, scene '%s' (brand: %s)",
                    entry.getSequenceNumber(), scene.getSceneTitle(), brandName);

            long deadline = scene.getEndTime()
                    .atZone(brandZone)
                    .toInstant()
                    .toEpochMilli();

            if (System.currentTimeMillis() >= deadline) {
                LOGGER.warnf("Scene '%s' deadline reached, skipping entry %d",
                        scene.getSceneTitle(), entry.getSequenceNumber());
                entry.setStatus(TimelineEntryStatus.SKIPPED);
                return;
            }

            LOGGER.infof("Emitting entry %d for scene '%s' (brand: %s)",
                    entry.getSequenceNumber(), scene.getSceneTitle(), brandName);
            entry.setStatus(TimelineEntryStatus.EMITTING);

            emitTimelineEntry(brandName, scene, entry, brandZone)
                    .subscribe().with(
                            v -> {
                                entry.setStatus(TimelineEntryStatus.COMPLETED);
                                LOGGER.infof("Completed entry %d for scene '%s'", entry.getSequenceNumber(), scene.getSceneTitle());
                            },
                            err -> {
                                entry.setStatus(TimelineEntryStatus.FAILED);
                                LOGGER.errorf(err, "Entry %d failed for scene '%s': %s",
                                        entry.getSequenceNumber(), scene.getSceneTitle(), err.getMessage());
                            }
                    );
        };

        long timerId = vertx.setTimer(delay, id -> {
            brandTimers.getOrDefault(brandName, new ConcurrentHashMap<>()).remove(entry.getSequenceNumber());
            task.run();
        });
        brandTimers.computeIfAbsent(brandName, k -> new ConcurrentHashMap<>())
                .put(entry.getSequenceNumber(), timerId);

        LOGGER.debugf("Created timer %d for entry %d (delay: %dms)", timerId, entry.getSequenceNumber(), delay);
    }

    private Uni<Void> emitTimelineEntry(String brandName, LiveScene scene, TimelineEntry entry, ZoneId brandZone) {
        return brandPool.get(brandName)
                .chain(stream -> {
                    if (entry.isHasJingle()) {
                        return sendJingleWithEntry(brandName, scene, entry, stream, brandZone);
                    }

                    // Get AI agent and send entry (TTS generated at last moment)
                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                            .chain(agent -> sendTimelineEntry(brandName, scene, entry, agent, stream, brandZone));
                });
    }

    private Uni<Void> sendTimelineEntry(String brandName,
                                        LiveScene scene,
                                        TimelineEntry entry,
                                        AiAgent agent,
                                        IStream stream,
                                        ZoneId brandZone) {

        LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);

        boolean djEnabled = djStateService.isDjEnabled(brandName);
        MergingType effectiveMixingStrategy = entry.getMixingStrategy();
        boolean shouldGenerateIntros = entry.isHasIntro() && djEnabled;

        if (!djEnabled && entry.isHasIntro()) {
            effectiveMixingStrategy = entry.getSongs().size() >= 2 
                ? MergingType.SONG_CROSSFADE_SONG 
                : MergingType.SONG_ONLY;
            LOGGER.infof("DJ disabled for brand %s, overriding mixing strategy from %s to %s",
                    brandName, entry.getMixingStrategy(), effectiveMixingStrategy);
        }

        // Generate TTS for each song that needs intro (at last moment before emission)
        List<Uni<IntroTtsGenerator.IntroAudioResult>> introUnis = new ArrayList<>();
        for (int i = 0; i < entry.getSongs().size(); i++) {
            if (shouldGenerateIntros) {
                introUnis.add(introTtsGenerator.generateIntroAudioFile(
                        scene, entry.getSongs().get(i), agent, stream, lang));
            } else {
                introUnis.add(Uni.createFrom().nullItem());
            }
        }

        MergingType finalMixingStrategy = effectiveMixingStrategy;
        return Uni.join().all(introUnis).andCollectFailures()
                .chain(intros -> {
                    // Build queue message from timeline data
                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                    dto.setMergingMethod(finalMixingStrategy);  // Use effective strategy
                    dto.setSceneId(scene.getSceneId());
                    dto.setSceneTitle(scene.getSceneTitle());
                    dto.setSequenceNumber(entry.getSequenceNumber());
                    dto.setPriority(9);

                    Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                    for (int i = 0; i < entry.getSongs().size(); i++) {
                        IntroTtsGenerator.IntroAudioResult intro = intros.get(i);

                        // Add intro if generated
                        if (intro != null) {
                            introMap.put(getIntroKeyByIndex(i),
                                    new IntroInfoDTO(intro.filePath(), intro.durationSeconds()));
                        }

                        // Add song info
                        songMap.put(getSongKeyByIndex(i),
                                new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                        entry.getSongs().get(i).getDurationSeconds()));
                    }

                    dto.setFilePaths(introMap);
                    dto.setSongs(songMap);

                    long deadline = scene.getEndTime()
                            .atZone(brandZone)
                            .toInstant()
                            .toEpochMilli();

                    dto.setSceneDeadlineTimestamp(deadline);

                    return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId());
                });
    }


    private Uni<Void> sendJingleWithEntry(String brandName,
                                          LiveScene scene,
                                          TimelineEntry entry,
                                          IStream stream,
                                          ZoneId brandZone) {
        return soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE, stream.getId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(jingles -> {
                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                    dto.setSceneId(scene.getSceneId());
                    dto.setSceneTitle(scene.getSceneTitle());
                    dto.setSequenceNumber(entry.getSequenceNumber());
                    dto.setPriority(9);

                    Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                    if (jingles.isEmpty()) {
                        dto.setMergingMethod(MergingType.SONG_ONLY);

                        for (int i = 0; i < entry.getSongs().size(); i++) {
                            songMap.put(getSongKeyByIndex(i),
                                    new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                            entry.getSongs().get(i).getDurationSeconds()));
                        }
                    } else {
                        SoundFragment jingle = jingles.get(ThreadLocalRandom.current().nextInt(jingles.size()));
                        dto.setMergingMethod(MergingType.FILLER_JINGLE);

                        int jingleDuration = jingle.getLength() != null
                                ? (int) jingle.getLength().toSeconds()
                                : DEFAULT_JINGLE_DURATION;

                        songMap.put(getSongKeyByIndex(0),
                                new SongInfoDTO(jingle.getId(), jingleDuration));

                        for (int i = 0; i < entry.getSongs().size(); i++) {
                            songMap.put(getSongKeyByIndex(i + 1),
                                    new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                            entry.getSongs().get(i).getDurationSeconds()));
                        }
                    }

                    long deadline = scene.getEndTime()
                            .atZone(brandZone)
                            .toInstant()
                            .toEpochMilli();

                    dto.setSceneDeadlineTimestamp(deadline);
                    dto.setFilePaths(introMap);
                    dto.setSongs(songMap);

                    return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId());
                })
                .onFailure().invoke(f ->
                        LOGGER.errorf(f, "Jingle flow failed for entry %d: %s",
                                entry.getSequenceNumber(), f.getMessage()));
    }

    public void cancelAll(String brandName) {
        ConcurrentHashMap<Integer, Long> timers = brandTimers.remove(brandName);
        if (timers != null) timers.values().forEach(vertx::cancelTimer);
    }

    public void cancelPending(String brandName, List<Integer> sequenceNumbers) {
        ConcurrentHashMap<Integer, Long> timers = brandTimers.get(brandName);
        if (timers == null) return;
        for (Integer seq : sequenceNumbers) {
            Long timerId = timers.remove(seq);
            if (timerId != null) vertx.cancelTimer(timerId);
        }
    }
}