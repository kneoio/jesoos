package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.IntroAudioResult;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.jesoos.model.stream.ILiveStream;
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
    private final MetricPublisher metricPublisher;
    private final SoundFragmentRepository soundFragmentRepository;

    @Inject
    public SongEmitter(IntroTtsGenerator introTtsGenerator,
                       QueueSupplier queueSupplier,
                       MetricPublisher metricPublisher,
                       SoundFragmentRepository soundFragmentRepository) {
        this.introTtsGenerator = introTtsGenerator;
        this.queueSupplier = queueSupplier;
        this.metricPublisher = metricPublisher;
        this.soundFragmentRepository = soundFragmentRepository;
    }

    public Uni<Void> send(String streamSlug,
                          boolean djOn,
                          LiveScene liveScene,
                          TimelineEntry entry,
                          AiAgent agent,
                          ILiveStream stream,
                          ZoneId brandZone,
                          int priority,
                          UUID emissionTraceId) {

        MixingType mixingStrategy = entry.getMixingStrategy();
        long sceneDeadlineForAivoxAwareness = liveScene.getEndTime()
                .atZone(brandZone)
                .toInstant()
                .toEpochMilli();

        if (djOn) {
            LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);
            boolean shouldGenerateIntros = entry.isHasIntro();
            List<Uni<IntroAudioResult>> introUnis = new ArrayList<>();
            for (int i = 0; i < entry.getSongs().size(); i++) {
                boolean needsIntro = shouldGenerateIntros && needsIntroAtIndex(mixingStrategy, i)
                        && hasIntroSource(entry.getSongs().get(i));
                if (needsIntro) {
                    SoundFragment previousSong = i > 0 ? entry.getSongs().get(i - 1).getSoundFragment() : null;
                    introUnis.add(introTtsGenerator.generateIntroAudioFile(
                            liveScene, entry.getSongs().get(i), previousSong, agent, stream, lang, entry.getSequenceNumber(), emissionTraceId));
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
                            SongInfoDTO info = buildSongInfo(sf, entry.getSongs().get(i).getDurationSeconds());
                            songMap.put(getSongKeyByIndex(i), info);
                        }

                        MixingType effectiveStrategy = introMap.isEmpty()
                                ? getNoIntroMergingTypes(entry)[0]
                                : finalMixingStrategy;
                        message.setMergingMethod(effectiveStrategy);
                        message.setFilePaths(introMap);
                        message.setSongs(songMap);

                        publishExpectedPlayOrder(streamSlug, entry, effectiveStrategy, liveScene.getTraceId(), emissionTraceId);
                        return queueSupplier.sendSongsToQueue(streamSlug, message, liveScene.getTraceId(), emissionTraceId)
                                .call(() -> recordPlays(stream, entry))
                                .chain(v -> notifyContributors(entry, intros, stream, agent, brandZone));
                    });
        } else {
            MixingType[] availableTypes = getNoIntroMergingTypes(entry);
            mixingStrategy = availableTypes[ThreadLocalRandom.current().nextInt(availableTypes.length)];

            SongQueueMessageDTO dto = createBaseSongQueueMessage(liveScene, entry, mixingStrategy, sceneDeadlineForAivoxAwareness, priority);

            Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
            Map<SongKey, SongInfoDTO> songMap = new HashMap<>();

            for (int i = 0; i < entry.getSongs().size(); i++) {
                var sf = entry.getSongs().get(i).getSoundFragment();
                songMap.put(getSongKeyByIndex(i), buildSongInfo(sf, entry.getSongs().get(i).getDurationSeconds()));
            }

            dto.setFilePaths(introMap);
            dto.setSongs(songMap);

            publishExpectedPlayOrder(streamSlug, entry, mixingStrategy, liveScene.getTraceId(), emissionTraceId);
            return queueSupplier.sendSongsToQueue(streamSlug, dto, liveScene.getTraceId(), emissionTraceId)
                    .call(() -> recordPlays(stream, entry));
        }
    }

    /**
     * Persists one on-air play per non-STREAM song, feeding radio's recency rotation
     * (`last_time_played_by_brand`). Brand-scoped only — owner-scoped OTS streams have no brand row to
     * update and are skipped. Fire-and-forget: a failed write only makes a song look slightly staler,
     * self-healing on the next play, and must never fail the emission.
     */
    private Uni<Void> recordPlays(ILiveStream stream, TimelineEntry entry) {
        if (stream == null || stream.getBrandId() == null) {
            return Uni.createFrom().voidItem();
        }
        UUID brandId = stream.getBrandId();
        List<Uni<Void>> writes = new ArrayList<>();
        for (SongEntry song : entry.getSongs()) {
            SoundFragment sf = song.getSoundFragment();
            if (sf == null || sf.getSource() == SourceType.STREAM) continue;
            writes.add(soundFragmentRepository.recordBrandPlay(brandId, sf.getId())
                    .onFailure().recoverWithItem((Void) null));
        }
        if (writes.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.join().all(writes).andCollectFailures().replaceWithVoid()
                .onFailure().recoverWithItem((Void) null);
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
        if (type == MixingType.SONG_INTRO_SONG) {
            return index == 1;
        }
        return true;
    }

    private static boolean hasIntroSource(SongEntry song) {
        return song.getPromptEntry() != null && song.getPromptEntry().hasIntroSource();
    }

    /**
     * ETA per song is a rough same-block offset only: the sum of intro/song durations ahead of it
     * within this newly-queued entry, up to and including its own intro. It cannot account for
     * whatever aivox is still emitting from a prior block, so it's an estimate, not a guarantee.
     */
    private Uni<Void> notifyContributors(TimelineEntry entry, List<IntroAudioResult> intros, ILiveStream stream, AiAgent agent, ZoneId brandZone) {
        List<Uni<Void>> notifications = new ArrayList<>();
        int offsetSeconds = 0;
        for (int i = 0; i < entry.getSongs().size(); i++) {
            IntroAudioResult intro = intros.get(i);
            int introDuration = intro != null ? intro.durationSeconds() : 0;
            if (intro != null) {
                int etaSeconds = offsetSeconds + introDuration;
                String previousSongTitle = i > 0 ? entry.getSongs().get(i - 1).getSoundFragment().getTitle() : null;
                notifications.add(introTtsGenerator.notifyContributorPlaying(entry.getSongs().get(i), stream, agent, etaSeconds, brandZone, previousSongTitle));
            }
            offsetSeconds += introDuration + entry.getSongs().get(i).getDurationSeconds();
        }
        if (notifications.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.join().all(notifications).andCollectFailures().replaceWithVoid();
    }

    private void publishExpectedPlayOrder(String streamSlug, TimelineEntry entry, MixingType mergingMethod, UUID parentTraceId, UUID emissionTraceId) {
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
        /*metricPublisher.publishMetric(streamSlug, MetricEventType.DEBUG, ProcessType.FLOW, "expected_play_order",
                Map.of("seq", entry.getSequenceNumber(), "mergingMethod", mergingMethod.name(),
                        "parentTraceId", parentTraceId == null ? "" : parentTraceId.toString(), "songs", songs),
                emissionTraceId);*/
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

    private SongInfoDTO buildSongInfo(SoundFragment sf, int durationSeconds) {
        SongInfoDTO info = new SongInfoDTO(sf.getId(), durationSeconds);
        info.setSourceType(sf.getSource());
        info.setStreamUrl(sf.getStreamUrl());
        if (sf.getType() == PlaylistItemType.PRERECORDED_ADVERTISEMENT) {
            info.setOverrideTitle("Advertisement");
            info.setOverrideArtist("");
        }
        return info;
    }
}
