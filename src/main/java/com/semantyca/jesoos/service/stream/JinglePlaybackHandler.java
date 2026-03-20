package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PendingSongEntry;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.dto.queue.livestream.IntroInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.IntroKey;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class JinglePlaybackHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(JinglePlaybackHandler.class);

    private final SoundFragmentService soundFragmentService;
    private final QueueSupplier queueSupplier;

    @Inject
    public JinglePlaybackHandler(
            SoundFragmentService soundFragmentService,
            QueueSupplier queueSupplier
    ) {
        this.soundFragmentService = soundFragmentService;
        this.queueSupplier = queueSupplier;
    }


    public Uni<Void> handleJingleAndSong(IStream stream, LiveScene scene, Set<UUID> sentSongs) {
        return soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE, stream.getId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(jingles -> {
                    List<PendingSongEntry> availableSongs = scene.getSongs().stream()
                            .filter(entry -> !sentSongs.contains(entry.getSoundFragment().getId()))
                            .toList();

                    if (availableSongs.isEmpty()) {
                        LOGGER.warn("Station '{}': No unfetched songs available in scene for jingle playback", stream.getSlugName());
                        return Uni.createFrom().voidItem();
                    }

                    PendingSongEntry selectedSongEntry = availableSongs.get(new Random().nextInt(availableSongs.size()));
                    SoundFragment selectedSong = selectedSongEntry.getSoundFragment();
                    sentSongs.add(selectedSong.getId());

                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                    dto.setSceneId(scene.getSceneId());
                    dto.setSceneTitle(scene.getSceneTitle());
                    dto.setSequenceNumber(sentSongs.size());
                    dto.setPriority(9);

                    Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                    if (jingles.isEmpty()) {
                        LOGGER.warn("Station '{}': No jingles available, playing song only", stream.getSlugName());
                        dto.setMergingMethod(MergingType.SONG_ONLY);
                        
                        songMap.put(getSongKeyByIndex(0), new SongInfoDTO(
                                selectedSong.getId(),
                                selectedSongEntry.getDurationSeconds()
                        ));
                    } else {
                        SoundFragment selectedJingle = jingles.get(new Random().nextInt(jingles.size()));
                        
                        LOGGER.info("Station '{}': Concatenating jingle '{}' with song '{}'",
                                stream.getSlugName(), selectedJingle.getTitle(), selectedSong.getTitle());
                        
                        dto.setMergingMethod(MergingType.FILLER_JINGLE);
                        
                        int jingleDuration = selectedJingle.getLength() != null
                                ? (int) selectedJingle.getLength().toSeconds()
                                : 180;
                        
                        songMap.put(getSongKeyByIndex(0), new SongInfoDTO(
                                selectedJingle.getId(),
                                jingleDuration
                        ));
                        songMap.put(getSongKeyByIndex(1), new SongInfoDTO(
                                selectedSong.getId(),
                                selectedSongEntry.getDurationSeconds()
                        ));
                    }

                    LocalDateTime sceneEndTime = scene.getScheduledEndTime();
                    long sceneDeadlineMillis = sceneEndTime
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli();
                    dto.setSceneDeadlineTimestamp(sceneDeadlineMillis);
                    dto.setFilePaths(introMap);
                    dto.setSongs(songMap);

                    LOGGER.info("Station '{}': Sending jingle playback to queue, traceId: {}", 
                            stream.getSlugName(), scene.getTraceId());
                    return queueSupplier.sendSongsToQueue(stream.getSlugName(), dto, scene.getTraceId());
                })
                .onFailure().invoke(failure -> LOGGER.error("Station '{}': Failed to process songs: {}, traceId: {}",
                        stream.getSlugName(), failure.getMessage(), scene.getTraceId(), failure))
                .onFailure().recoverWithNull();
    }
}
