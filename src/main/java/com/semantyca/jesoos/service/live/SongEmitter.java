package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.*;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.semantyca.jesoos.util.AiHelperUtils.getIntroKeyByIndex;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class SongEmitter {

    private final IntroTtsGenerator introTtsGenerator;
    private final QueueSupplier queueSupplier;
    private final DjStateService djStateService;

    @Inject
    public SongEmitter(IntroTtsGenerator introTtsGenerator,
                       QueueSupplier queueSupplier,
                       DjStateService djStateService) {
        this.introTtsGenerator = introTtsGenerator;
        this.queueSupplier = queueSupplier;
        this.djStateService = djStateService;
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
            List<Uni<IntroAudioResult>> introUnis = new ArrayList<>();
            for (int i = 0; i < entry.getSongs().size(); i++) {
                boolean needsIntro = shouldGenerateIntros && needsIntroAtIndex(mixingStrategy, i);
                if (needsIntro) {
                    introUnis.add(introTtsGenerator.generateIntroAudioFile(
                            liveScene, entry.getSongs().get(i), agent, stream, lang));
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
                                introMap.put(getIntroKeyByIndex(introIndex++),
                                        new IntroInfoDTO(intro.filePath(), intro.durationSeconds()));
                            }

                            songMap.put(getSongKeyByIndex(i),
                                    new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                            entry.getSongs().get(i).getDurationSeconds()));
                        }

                        MixingType effectiveStrategy = introMap.isEmpty()
                                ? getNoIntroMergingTypes(entry)[0]
                                : finalMixingStrategy;
                        message.setMergingMethod(effectiveStrategy);
                        message.setFilePaths(introMap);
                        message.setSongs(songMap);

                        return queueSupplier.sendSongsToQueue(brandName, message, liveScene.getTraceId());
                    });
        } else {
            MixingType[] availableTypes = getNoIntroMergingTypes(entry);
            mixingStrategy = availableTypes[ThreadLocalRandom.current().nextInt(availableTypes.length)];

            SongQueueMessageDTO dto = createBaseSongQueueMessage(liveScene, entry, mixingStrategy, sceneDeadlineForAivoxAwareness, priority);

            Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
            Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

            for (int i = 0; i < entry.getSongs().size(); i++) {
                songMap.put(getSongKeyByIndex(i),
                        new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                entry.getSongs().get(i).getDurationSeconds()));
            }

            dto.setFilePaths(introMap);
            dto.setSongs(songMap);

            return queueSupplier.sendSongsToQueue(brandName, dto, liveScene.getTraceId());
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
        return dto;
    }

    private static boolean needsIntroAtIndex(MixingType type, int index) {
        // SONG_INTRO_SONG: only the second song (index 1) gets an intro
        if (type == MixingType.SONG_INTRO_SONG) {
            return index == 1;
        }
        return true;
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
