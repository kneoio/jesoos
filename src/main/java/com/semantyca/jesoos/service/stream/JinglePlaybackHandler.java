package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PendingSongEntry;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.semantyca.jesoos.util.AiHelperUtils.getSongKeyByIndex;

@ApplicationScoped
public class JinglePlaybackHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JinglePlaybackHandler.class);

    private static final int DEFAULT_JINGLE_DURATION = 10; // safer fallback

    private final SoundFragmentService soundFragmentService;
    private final QueueSupplier queueSupplier;

    // persistent per scene
    private final Map<UUID, Set<UUID>> scenePlayedSongs = new ConcurrentHashMap<>();

    @Inject
    public JinglePlaybackHandler(SoundFragmentService soundFragmentService,
                                 QueueSupplier queueSupplier) {
        this.soundFragmentService = soundFragmentService;
        this.queueSupplier = queueSupplier;
    }

    public Uni<Void> handleJingleAndSong(IStream stream,
                                         LiveScene scene,
                                         int sequenceNumber) {

        UUID sceneId = scene.getSceneId();
        scenePlayedSongs.putIfAbsent(sceneId, ConcurrentHashMap.newKeySet());
        Set<UUID> played = scenePlayedSongs.get(sceneId);

        return soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE, stream.getId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(jingles -> {

                    List<PendingSongEntry> available = scene.getSongs().stream()
                            .filter(s -> !played.contains(s.getSoundFragment().getId()))
                            .toList();

                    if (available.isEmpty()) {
                        LOGGER.warn("No unique songs left for scene {}", sceneId);
                        return Uni.createFrom().voidItem();
                    }

                    PendingSongEntry selectedSongEntry =
                            available.get(ThreadLocalRandom.current().nextInt(available.size()));

                    SoundFragment selectedSong = selectedSongEntry.getSoundFragment();
                    played.add(selectedSong.getId());

                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                    dto.setSceneId(sceneId);
                    dto.setSceneTitle(scene.getSceneTitle());
                    dto.setSequenceNumber(sequenceNumber);
                    dto.setPriority(9);

                    Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

                    if (jingles.isEmpty()) {
                        dto.setMergingMethod(MergingType.SONG_ONLY);

                        songMap.put(getSongKeyByIndex(0),
                                new SongInfoDTO(selectedSong.getId(),
                                        selectedSongEntry.getDurationSeconds()));
                    } else {
                        SoundFragment jingle =
                                jingles.get(ThreadLocalRandom.current().nextInt(jingles.size()));

                        dto.setMergingMethod(MergingType.FILLER_JINGLE);

                        int jingleDuration = jingle.getLength() != null
                                ? (int) jingle.getLength().toSeconds()
                                : DEFAULT_JINGLE_DURATION;

                        songMap.put(getSongKeyByIndex(0),
                                new SongInfoDTO(jingle.getId(), jingleDuration));

                        songMap.put(getSongKeyByIndex(1),
                                new SongInfoDTO(selectedSong.getId(),
                                        selectedSongEntry.getDurationSeconds()));
                    }

                    long deadline = scene.getScheduledEndTime()
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli();

                    dto.setSceneDeadlineTimestamp(deadline);
                    dto.setFilePaths(introMap);
                    dto.setSongs(songMap);

                    return queueSupplier.sendSongsToQueue(stream.getSlugName(), dto, scene.getTraceId());
                })
                .onFailure().invoke(f ->
                        LOGGER.error("Jingle flow failed: {}", f.getMessage(), f));
    }
}