package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
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
                          LiveScene scene,
                          TimelineEntry entry,
                          AiAgent agent,
                          IStream stream,
                          ZoneId brandZone) {

        MergingType mixingStrategy = entry.getMixingStrategy();
        boolean djEnabled = djStateService.isDjEnabled(brandName);
        long sceneDeadlineForAivoxAwareness = scene.getEndTime()
                .atZone(brandZone)
                .toInstant()
                .toEpochMilli();

        if (djEnabled) {
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
                        SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, finalMixingStrategy, sceneDeadlineForAivoxAwareness);

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

            SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, mixingStrategy, sceneDeadlineForAivoxAwareness);

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
        dto.setPriority(entry.isHasIntro() ? 9 : 10);
        dto.setSceneDeadlineTimestamp(deadline);
        return dto;
    }

    private static MergingType @NotNull [] getNoIntroMergingTypes(TimelineEntry entry) {
        MergingType[] availableTypes;
        if (entry.getSongs().size() == 2) {
            availableTypes = new MergingType[]{
                    MergingType.SONG_CROSSFADE_SONG
            };
        } else {
            availableTypes = new MergingType[]{
                    MergingType.SONG_ONLY
            };
        }
        return availableTypes;
    }
}
