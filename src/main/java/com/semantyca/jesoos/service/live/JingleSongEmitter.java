package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.jesoos.model.stream.ILiveStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.semantyca.jesoos.service.live.GeneratedContentEmitter.DEFAULT_JINGLE_DURATION;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class JingleSongEmitter {
    private static final Logger LOGGER = Logger.getLogger(JingleSongEmitter.class);

    private final SoundFragmentService soundFragmentService;
    private final QueueSupplier queueSupplier;
    private final IntroTtsGenerator introTtsGenerator;

    @Inject
    public JingleSongEmitter(SoundFragmentService soundFragmentService,
                             QueueSupplier queueSupplier,
                             IntroTtsGenerator introTtsGenerator) {
        this.soundFragmentService = soundFragmentService;
        this.queueSupplier = queueSupplier;
        this.introTtsGenerator = introTtsGenerator;
    }

    public Uni<Void> send(String streamSlug,
                          boolean djOn,
                          LiveScene scene,
                          TimelineEntry entry,
                          AiAgent agent,
                          ILiveStream stream,
                          ZoneId brandZone,
                          int priority,
                          UUID emissionTraceId) {
        // DJ on/off is a per-brand toggle for radio; OTS callers force this true always -- a
        // personal one-time stream always talks, regardless of the master brand's live toggle.
        boolean djEnabled = djOn;
        long sceneDeadline = scene.getEndTime().atZone(brandZone).toInstant().toEpochMilli();

        if (stream.getBrandId() == null) {
            LOGGER.warnf("Owner-scoped OTS stream '%s' has no master brand — jingles are sourced from a " +
                    "brand catalog, so this entry will fall back to plain song only", streamSlug);
        }

        return soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE, stream.getBrandId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(jingles -> {
                    if (jingles.isEmpty()) {
                        Map<SongKey, SongInfoDTO> songMap = new HashMap<>();
                        for (int i = 0; i < entry.getSongs().size(); i++) {
                            songMap.put(getSongKeyByIndex(i),
                                    new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                            entry.getSongs().get(i).getDurationSeconds()));
                        }
                        SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, MixingType.SONG_ONLY, sceneDeadline, priority);
                        dto.setFilePaths(new HashMap<>());
                        dto.setSongs(songMap);
                        return queueSupplier.sendSongsToQueue(streamSlug, dto, scene.getTraceId());
                    }

                    SoundFragment jingle = jingles.get(ThreadLocalRandom.current().nextInt(jingles.size()));
                    int jingleDuration = jingle.getLength() != null ? (int) jingle.getLength().toSeconds() : DEFAULT_JINGLE_DURATION;

                    if (djEnabled && entry.isHasIntro() && hasIntroSource(entry)) {
                        LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);
                        return introTtsGenerator.generateIntroAudioFile(scene, entry.getSongs().getFirst(), null, agent, stream, lang, entry.getSequenceNumber(), emissionTraceId)
                                .chain(introResult -> {
                                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();
                                    songMap.put(SongKey.JINGLE_1, new SongInfoDTO(jingle.getId(), jingleDuration));
                                    songMap.put(SongKey.SONG_1, new SongInfoDTO(
                                            entry.getSongs().getFirst().getSoundFragment().getId(),
                                            entry.getSongs().getFirst().getDurationSeconds()));

                                    IntroInfoDTO introDto = new IntroInfoDTO(introResult.filePath(), introResult.durationSeconds());
                                    introDto.setGain(introResult.gain());
                                    introDto.setEngineType(introResult.engineType());

                                    SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, MixingType.JINGLE_INTRO_SONG, sceneDeadline, priority);
                                    dto.setFilePaths(Map.of(IntroKey.INTRO_1, introDto));
                                    dto.setSongs(songMap);
                                    return queueSupplier.sendSongsToQueue(streamSlug, dto, scene.getTraceId());
                                });
                    }

                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();
                    songMap.put(SongKey.JINGLE_1, new SongInfoDTO(jingle.getId(), jingleDuration));
                    for (int i = 0; i < entry.getSongs().size(); i++) {
                        songMap.put(getSongKeyByIndex(i),
                                new SongInfoDTO(entry.getSongs().get(i).getSoundFragment().getId(),
                                        entry.getSongs().get(i).getDurationSeconds()));
                    }
                    SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, MixingType.FILLER_JINGLE, sceneDeadline, priority);
                    dto.setFilePaths(new HashMap<>());
                    dto.setSongs(songMap);
                    return queueSupplier.sendSongsToQueue(streamSlug, dto, scene.getTraceId());
                });
    }

    private static boolean hasIntroSource(TimelineEntry entry) {
        if (entry.getSongs() == null || entry.getSongs().isEmpty()) {
            return false;
        }
        var promptEntry = entry.getSongs().getFirst().getPromptEntry();
        return promptEntry != null && promptEntry.hasIntroSource();
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
}
