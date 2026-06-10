package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.repository.UserAdRepository;
import com.semantyca.jesoos.service.live.generated.AbstractGeneratedContentService.GenerationResult;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PromptEntry;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.generated.AbstractGeneratedContentService;
import com.semantyca.jesoos.service.live.generated.GeneratedAdService;
import com.semantyca.jesoos.service.live.generated.GeneratedNewsService;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.model.PlayHistory;
import com.semantyca.mixpla.dto.queue.livestream.IntroInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.IntroKey;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.PromptType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.semantyca.mixpla.dto.queue.livestream.SongKey.BACKGROUND_LOOP;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.GENERATED_CONTENT;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.JINGLE_INTRO;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.JINGLE_OUTRO;

@ApplicationScoped
public class GeneratedContentEmitter {
    private static final Logger LOGGER = Logger.getLogger(GeneratedContentEmitter.class);
    private static final int DEFAULT_JINGLE_DURATION = 10;
    private static final int DEFAULT_BACKGROUND_DURATION = 180;

    private final GeneratedNewsService generatedNewsService;
    private final GeneratedAdService generatedAdService;
    private final PromptService promptService;
    private final SoundFragmentService soundFragmentService;
    private final QueueSupplier queueSupplier;
    private final IntroTtsGenerator introTtsGenerator;
    private final MetricPublisher metricPublisher;
    private final UserAdRepository userAdRepository;

    @Inject
    public GeneratedContentEmitter(GeneratedNewsService generatedNewsService,
                                   GeneratedAdService generatedAdService,
                                   PromptService promptService,
                                   SoundFragmentService soundFragmentService,
                                   QueueSupplier queueSupplier,
                                   IntroTtsGenerator introTtsGenerator,
                                   MetricPublisher metricPublisher,
                                   UserAdRepository userAdRepository) {
        this.generatedNewsService = generatedNewsService;
        this.generatedAdService = generatedAdService;
        this.promptService = promptService;
        this.soundFragmentService = soundFragmentService;
        this.queueSupplier = queueSupplier;
        this.introTtsGenerator = introTtsGenerator;
        this.metricPublisher = metricPublisher;
        this.userAdRepository = userAdRepository;
    }

    public Uni<Void> send(String brandName,
                          LiveScene scene,
                          TimelineEntry entry,
                          AiAgent agent,
                          IStream stream,
                          ZoneId brandZone,
                          int priority) {

        List<ScenePrompt> contentPrompts = scene.getContentPrompts();
        if (contentPrompts == null || contentPrompts.isEmpty()) {
            return Uni.createFrom().failure(
                    new IllegalStateException("No content prompts configured for scene: " + scene.getSceneTitle()));
        }
        UUID promptId = contentPrompts.getFirst().getPromptId();
        LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);

        Map<String, String> artefacts = scene.getMixingArtefacts();

        Uni<List<SoundFragment>> jinglesUni = (artefacts != null && artefacts.containsKey(PlaylistItemType.JINGLE_INTRO.name()))
                ? soundFragmentService.getById(UUID.fromString(artefacts.get(PlaylistItemType.JINGLE_INTRO.name())))
                        .map(List::of)
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                : soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE_INTRO, stream.getMasterBrandId())
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

        Uni<List<SoundFragment>> songsUni = (artefacts != null && artefacts.containsKey(PlaylistItemType.BACKGROUND_LOOP.name()))
                ? soundFragmentService.getById(UUID.fromString(artefacts.get(PlaylistItemType.BACKGROUND_LOOP.name())))
                        .map(List::of)
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                : soundFragmentService.getByTypeAndBrand(PlaylistItemType.BACKGROUND_LOOP, stream.getMasterBrandId())
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

        List<ScenePrompt> activeIntroPrompts = scene.getIntroPrompts() == null ? List.of() :
                scene.getIntroPrompts().stream().filter(ScenePrompt::isActive).toList();

        record GeneratedResult(SoundFragment fragment, UUID adId, MixingType mixingType) {}

        Uni<GeneratedResult> generatedUni = promptService.getById(promptId, SuperUser.build())
                .flatMap(prompt -> {
                    boolean isAd = PromptType.GENERATOR.equals(prompt.getPromptType())
                            && prompt.getTitle().toLowerCase().contains("ad");
                    AbstractGeneratedContentService service = isAd ? generatedAdService : generatedNewsService;
                    MixingType mixingType = scene.getMixingType();
                    return service.generateAudio(promptId, agent, stream, lang, scene)
                            .map(r -> new GeneratedResult(r.fragment(), r.adId(), mixingType));
                });

        return Uni.combine().all().unis(jinglesUni, songsUni, generatedUni).asTuple()
                .chain(tuple -> {
                    List<SoundFragment> jingles = tuple.getItem1();
                    List<SoundFragment> songs = tuple.getItem2();
                    SoundFragment generated = tuple.getItem3().fragment();
                    UUID adId = tuple.getItem3().adId();
                    MixingType mixingType = tuple.getItem3().mixingType();

                    if (jingles.isEmpty()) {
                        LOGGER.warnf("No jingles available for brand '%s', skipping generated content", brandName);
                        metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW,
                                "no_jingles_for_generated_content",
                                Map.of("scene", scene.getSceneTitle(), "sceneId", scene.getSceneId()),
                                scene.getTraceId());
                        return Uni.createFrom().voidItem();
                    }
                    if (songs.isEmpty()) {
                        LOGGER.warnf("No songs available for background for brand '%s', skipping generated content", brandName);
                        metricPublisher.publishMetric(brandName, MetricEventType.ERROR, ProcessType.FLOW,
                                "no_background_songs_for_generated_content",
                                Map.of("scene", scene.getSceneTitle(), "sceneId", scene.getSceneId()),
                                scene.getTraceId());
                        return Uni.createFrom().voidItem();
                    }

                    SoundFragment jingle1 = jingles.get(ThreadLocalRandom.current().nextInt(jingles.size()));
                    SoundFragment jingle2 = jingles.get(ThreadLocalRandom.current().nextInt(jingles.size()));
                    SoundFragment background = songs.get(ThreadLocalRandom.current().nextInt(songs.size()));

                    long deadline = scene.getEndTime().atZone(brandZone).toInstant().toEpochMilli();

                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();
                    songMap.put(JINGLE_INTRO, new SongInfoDTO(jingle1.getId(), jingleDuration(jingle1)));
                    songMap.put(JINGLE_OUTRO, new SongInfoDTO(jingle2.getId(), jingleDuration(jingle2)));
                    songMap.put(GENERATED_CONTENT, new SongInfoDTO(generated.getId(), songDuration(generated)));
                    if (mixingType != MixingType.JINGLE_GENERATED_JINGLE) {
                        songMap.put(BACKGROUND_LOOP, new SongInfoDTO(background.getId(), songDuration(background)));
                    }

                    if (mixingType == MixingType.INTRO_JINGLE_GENERATED_JINGLE_WITH_BACKGROUND) {
                        ScenePrompt selectedIntroPrompt = activeIntroPrompts.get(
                                ThreadLocalRandom.current().nextInt(activeIntroPrompts.size()));
                        PromptEntry promptEntry = new PromptEntry();
                        promptEntry.setPromptId(selectedIntroPrompt.getPromptId());
                        SongEntry introSongEntry = new SongEntry(generated, promptEntry, entry.getSequenceNumber());

                        return introTtsGenerator.generateIntroAudioFile(scene, introSongEntry, agent, stream, lang, entry.getSequenceNumber(), UUID.randomUUID())
                                .chain(introResult -> {
                                    SongQueueMessageDTO dto = buildDto(mixingType, scene, entry, deadline, priority);
                                    Map<IntroKey, IntroInfoDTO> introMap = new HashMap<>();
                                    IntroInfoDTO introDto = new IntroInfoDTO(introResult.filePath(), introResult.durationSeconds());
                                    introDto.setGain(introResult.gain());
                                    introDto.setEngineType(introResult.engineType());
                                    introMap.put(IntroKey.INTRO_1, introDto);
                                    dto.setFilePaths(introMap);
                                    dto.setSongs(songMap);
                                    return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId())
                                            .call(() -> recordPlayHistory(adId, generated, agent));
                                });
                    }

                    SongQueueMessageDTO dto = buildDto(mixingType, scene, entry, deadline, priority);
                    dto.setSongs(songMap);
                    return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId())
                            .call(() -> recordPlayHistory(adId, generated, agent));
                });
    }

    private Uni<Void> recordPlayHistory(UUID adId, SoundFragment fragment, AiAgent agent) {
        if (adId == null) return Uni.createFrom().voidItem();
        int durationSeconds = fragment.getLength() != null ? (int) fragment.getLength().getSeconds() : 0;
        PlayHistory entry = new PlayHistory(OffsetDateTime.now(), durationSeconds, null, agent.getName());
        return userAdRepository.addPlayHistoryEntry(adId, entry)
                .invoke(() -> LOGGER.infof("Play history recorded for ad %s", adId));
    }

    private static SongQueueMessageDTO buildDto(MixingType type, LiveScene scene, TimelineEntry entry, long deadline, int priority) {
        SongQueueMessageDTO dto = new SongQueueMessageDTO();
        dto.setMergingMethod(type);
        dto.setSceneId(scene.getSceneId());
        dto.setSceneTitle(scene.getSceneTitle());
        dto.setSequenceNumber(entry.getSequenceNumber());
        dto.setPriority(priority);
        dto.setSceneDeadlineTimestamp(deadline);
        return dto;
    }

    private int jingleDuration(SoundFragment jingle) {
        return jingle.getLength() != null ? (int) jingle.getLength().toSeconds() : DEFAULT_JINGLE_DURATION;
    }

    private int songDuration(SoundFragment song) {
        return song.getLength() != null ? (int) song.getLength().toSeconds() : DEFAULT_BACKGROUND_DURATION;
    }
}
