package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.semantyca.jesoos.util.AiHelperUtils.getIntroKeyByIndex;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class SongEmitter {

    private final IntroTtsGenerator introTtsGenerator;
    private final QueueSupplier queueSupplier;
    private final DjStateService djStateService;
    private final MetricPublisher metricPublisher;

    @Inject
    public SongEmitter(IntroTtsGenerator introTtsGenerator,
                       QueueSupplier queueSupplier,
                       DjStateService djStateService,
                       MetricPublisher metricPublisher) {
        this.introTtsGenerator = introTtsGenerator;
        this.queueSupplier = queueSupplier;
        this.djStateService = djStateService;
        this.metricPublisher = metricPublisher;
    }

    public Uni<Void> send(String brandName,
                          LiveScene liveScene,
                          TimelineEntry entry,
                          AiAgent agent,
                          IStream stream,
                          ZoneId brandZone,
                          int priority) {

        MixingType mixingStrategy = entry.getMixingStrategy();
        boolean djEnabled = djStateService.isDjEnabled(brandName);
        long sceneDeadlineForAivoxAwareness = liveScene.getEndTime()
                .atZone(brandZone)
                .toInstant()
                .toEpochMilli();

        if (djEnabled) {
            LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);
            boolean shouldGenerateIntros = entry.isHasIntro();
            UUID entryTraceId = UUID.randomUUID();
            List<Uni<IntroAudioResult>> introUnis = new ArrayList<>();
            for (int i = 0; i < entry.getSongs().size(); i++) {
                boolean needsIntro = shouldGenerateIntros && needsIntroAtIndex(mixingStrategy, i);
                if (needsIntro) {
                    introUnis.add(introTtsGenerator.generateIntroAudioFile(
                            liveScene, entry.getSongs().get(i), agent, stream, lang, entry.getSequenceNumber(), entryTraceId));
                } else {
                    introUnis.add(Uni.createFrom().nullItem());
                }
            }

            MixingType finalMixingStrategy = mixingStrategy;
            return Uni.join().all(introUnis).andCollectFailures()
                    .chain(intros -> {
                        SongQueueMessageDTO message = createBaseSongQueueMessage(liveScene, entry, finalMixingStrategy, sceneDeadlineForAivoxAwareness, priority);

                        Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                        Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                        int introIndex = 0;
                        for (int i = 0; i < entry.getSongs().size(); i++) {
                            IntroAudioResult intro = intros.get(i);

                            if (intro != null) {
                                IntroInfoDTO introDto = new IntroInfoDTO(intro.filePath(), intro.durationSeconds());
                                introDto.setGain(intro.gain());
                                introDto.setEngineType(intro.engineType());
                                introMap.put(getIntroKeyByIndex(introIndex++), introDto);
                            }

                            var sf = entry.getSongs().get(i).getSoundFragment();
                            SongInfoDTO info = new SongInfoDTO(sf.getId(), entry.getSongs().get(i).getDurationSeconds());
                            info.setSourceType(sf.getSource());
                            info.setStreamUrl(sf.getStreamUrl());
                            songMap.put(getSongKeyByIndex(i), info);
                        }

                        MixingType effectiveStrategy = introMap.isEmpty()
                                ? getNoIntroMergingTypes(entry)[0]
                                : finalMixingStrategy;
                        message.setMergingMethod(effectiveStrategy);
                        message.setFilePaths(introMap);
                        message.setSongs(songMap);

                        UUID emissionTraceId = UUID.randomUUID();
                        publishExpectedPlayOrder(brandName, entry, effectiveStrategy, liveScene.getTraceId(), emissionTraceId);
                        return queueSupplier.sendSongsToQueue(brandName, message, liveScene.getTraceId(), emissionTraceId);
                    });
        } else {
            MixingType[] availableTypes = getNoIntroMergingTypes(entry);
            mixingStrategy = availableTypes[ThreadLocalRandom.current().nextInt(availableTypes.length)];

            SongQueueMessageDTO dto = createBaseSongQueueMessage(liveScene, entry, mixingStrategy, sceneDeadlineForAivoxAwareness, priority);

            Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
            Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

            for (int i = 0; i < entry.getSongs().size(); i++) {
                var sf = entry.getSongs().get(i).getSoundFragment();
                SongInfoDTO info = new SongInfoDTO(sf.getId(), entry.getSongs().get(i).getDurationSeconds());
                info.setSourceType(sf.getSource());
                info.setStreamUrl(sf.getStreamUrl());
                songMap.put(getSongKeyByIndex(i), info);
            }

            dto.setFilePaths(introMap);
            dto.setSongs(songMap);

            UUID emissionTraceId = UUID.randomUUID();
            publishExpectedPlayOrder(brandName, entry, mixingStrategy, liveScene.getTraceId(), emissionTraceId);
            return queueSupplier.sendSongsToQueue(brandName, dto, liveScene.getTraceId(), emissionTraceId);
        }
    }

    private static SongQueueMessageDTO createBaseSongQueueMessage(LiveScene scene, TimelineEntry entry, MixingType mixingStrategy, long deadline, int priority) {
        SongQueueMessageDTO dto = new SongQueueMessageDTO();
        dto.setMergingMethod(mixingStrategy);
        dto.setSceneId(scene.getSceneId());
        dto.setSceneTitle(scene.getSceneTitle());
        dto.setSequenceNumber(entry.getSequenceNumber());
        dto.setPriority(priority);
        dto.setSceneDeadlineTimestamp(deadline);
        dto.setOtsSlugName(scene.getOtsSlugName());
        return dto;
    }

    private static boolean needsIntroAtIndex(MixingType type, int index) {
        // SONG_INTRO_SONG: only the second song (index 1) gets an intro
        if (type == MixingType.SONG_INTRO_SONG) {
            return index == 1;
        }
        return true;
    }

    public Uni<Void> sendWithCustomIntro(String brandName,
                                          LiveScene liveScene,
                                          TimelineEntry entry,
                                          String customIntroText,
                                          AiAgent agent,
                                          ZoneId brandZone,
                                          int priority) {

        LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);
        long sceneDeadlineForAivoxAwareness = liveScene.getEndTime()
                .atZone(brandZone)
                .toInstant()
                .toEpochMilli();

        return introTtsGenerator.generateCustomIntroAudioFile(
                customIntroText,
                agent,
                lang,
                liveScene.getSceneTitle(),
                liveScene.getTraceId(),
                brandName,
                entry.getSequenceNumber()
        ).chain(introResult -> {
            SongQueueMessageDTO message = createBaseSongQueueMessage(liveScene, entry, MixingType.INTRO_SONG, sceneDeadlineForAivoxAwareness, priority);

            Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
            IntroInfoDTO introDto = new IntroInfoDTO(introResult.filePath(), introResult.durationSeconds());
            introDto.setGain(introResult.gain());
            introDto.setEngineType(introResult.engineType());
            introMap.put(IntroKey.INTRO_1, introDto);

            Map<SongKey, SongInfoDTO> songMap = new HashMap<>();
            songMap.put(SongKey.SONG_1, new SongInfoDTO(
                    entry.getSongs().getFirst().getSoundFragment().getId(),
                    entry.getSongs().getFirst().getDurationSeconds()));

            message.setFilePaths(introMap);
            message.setSongs(songMap);

            return queueSupplier.sendSongsToQueue(brandName, message, liveScene.getTraceId());
        });
    }

    private void publishExpectedPlayOrder(String brandName, TimelineEntry entry, MixingType mergingMethod, UUID parentTraceId, UUID emissionTraceId) {
        List<Map<String, Object>> songs = new ArrayList<>();
        for (int i = 0; i < entry.getSongs().size(); i++) {
            var sf = entry.getSongs().get(i).getSoundFragment();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order", i + 1);
            item.put("songId", sf.getId().toString());
            item.put("title", sf.getTitle());
            item.put("artist", sf.getArtist());
            songs.add(item);
        }
        metricPublisher.publishMetric(brandName, MetricEventType.DEBUG, ProcessType.FLOW, "expected_play_order",
                Map.of("seq", entry.getSequenceNumber(), "mergingMethod", mergingMethod.name(),
                        "parentTraceId", parentTraceId == null ? "" : parentTraceId.toString(), "songs", songs),
                emissionTraceId);
    }

    private static MixingType @NotNull [] getNoIntroMergingTypes(TimelineEntry entry) {
        MixingType[] availableTypes;
        if (entry.getSongs().size() == 2) {
            availableTypes = new MixingType[]{
                    MixingType.SONG_CROSSFADE_SONG
            };
        } else {
            availableTypes = new MixingType[]{
                    MixingType.SONG_ONLY
            };
        }
        return availableTypes;
    }
}
