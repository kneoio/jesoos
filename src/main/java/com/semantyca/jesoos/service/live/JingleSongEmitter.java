package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.semantyca.jesoos.service.live.StaggeredSongScheduler.DEFAULT_JINGLE_DURATION;
import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class JingleSongEmitter {

    private final SoundFragmentService soundFragmentService;
    private final QueueSupplier queueSupplier;

    @Inject
    public JingleSongEmitter(SoundFragmentService soundFragmentService,
                             QueueSupplier queueSupplier) {
        this.soundFragmentService = soundFragmentService;
        this.queueSupplier = queueSupplier;
    }

    public Uni<Void> send(String brandName,
                          LiveScene scene,
                          TimelineEntry entry,
                          IStream stream,
                          ZoneId brandZone) {
        return soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE, stream.getId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(jingles -> {
                    long sceneDeadlineForAivoxAwareness = scene.getEndTime()
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

                    SongQueueMessageDTO dto = createBaseSongQueueMessage(scene, entry, mergingType, sceneDeadlineForAivoxAwareness);
                    dto.setFilePaths(new HashMap<>());
                    dto.setSongs(songMap);
                    return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId());
                });
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
}
