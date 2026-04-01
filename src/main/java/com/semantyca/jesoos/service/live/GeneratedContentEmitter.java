package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.messaging.QueueSupplier;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.live.generated.AbstractGeneratedContentService;
import com.semantyca.jesoos.service.live.generated.GeneratedNewsService;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.semantyca.mixpla.dto.queue.livestream.IntroKey.NEWS_BLOCK;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.*;

@ApplicationScoped
public class GeneratedContentEmitter {
    private static final Logger LOGGER = Logger.getLogger(GeneratedContentEmitter.class);
    private static final int DEFAULT_JINGLE_DURATION = 10;
    private static final int DEFAULT_BACKGROUND_DURATION = 180;

    private final GeneratedNewsService generatedNewsService;
    private final SoundFragmentService soundFragmentService;
    private final QueueSupplier queueSupplier;

    @Inject
    public GeneratedContentEmitter(GeneratedNewsService generatedNewsService,
                                   SoundFragmentService soundFragmentService,
                                   QueueSupplier queueSupplier) {
        this.generatedNewsService = generatedNewsService;
        this.soundFragmentService = soundFragmentService;
        this.queueSupplier = queueSupplier;
    }

    public Uni<Void> send(String brandName,
                          LiveScene scene,
                          TimelineEntry entry,
                          AiAgent agent,
                          IStream stream,
                          ZoneId brandZone) {

        List<ScenePrompt> contentPrompts = scene.getContentPrompts();
        if (contentPrompts == null || contentPrompts.isEmpty()) {
            return Uni.createFrom().failure(
                    new IllegalStateException("No content prompts configured for scene: " + scene.getSceneTitle()));
        }
        UUID promptId = contentPrompts.getFirst().getPromptId();
        LanguageTag lang = AiHelperUtils.selectLanguageByWeight(agent);

        Uni<List<SoundFragment>> jinglesUni = soundFragmentService
                .getByTypeAndBrand(PlaylistItemType.JINGLE_INTRO, stream.getId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

        Uni<List<SoundFragment>> songsUni = soundFragmentService
                .getByTypeAndBrand(PlaylistItemType.BACKGROUND_LOOP, stream.getId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

        Uni<AbstractGeneratedContentService.AudioGenerationResult> ttsUni =
                generatedNewsService.generateAudio(promptId, agent, stream, lang, scene.getSceneTitle(), scene.getTraceId());

        return Uni.combine().all().unis(jinglesUni, songsUni, ttsUni).asTuple()
                .chain(tuple -> {
                    List<SoundFragment> jingles = tuple.getItem1();
                    List<SoundFragment> songs = tuple.getItem2();
                    AbstractGeneratedContentService.AudioGenerationResult tts = tuple.getItem3();

                    if (jingles.isEmpty()) {
                        LOGGER.warnf("No jingles available for brand '%s', skipping generated content", brandName);
                        return Uni.createFrom().voidItem();
                    }
                    if (songs.isEmpty()) {
                        LOGGER.warnf("No songs available for background for brand '%s', skipping generated content", brandName);
                        return Uni.createFrom().voidItem();
                    }

                    SoundFragment jingle1 = jingles.get(ThreadLocalRandom.current().nextInt(jingles.size()));
                    SoundFragment jingle2 = jingles.get(ThreadLocalRandom.current().nextInt(jingles.size()));
                    SoundFragment background = songs.get(ThreadLocalRandom.current().nextInt(songs.size()));

                    long deadline = scene.getEndTime().atZone(brandZone).toInstant().toEpochMilli();

                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                    dto.setMergingMethod(MergingType.JINGLE_GENERATED_JINGLE_WITH_BACKGROUND);
                    dto.setSceneId(scene.getSceneId());
                    dto.setSceneTitle(scene.getSceneTitle());
                    dto.setSequenceNumber(entry.getSequenceNumber());
                    dto.setPriority(9);
                    dto.setSceneDeadlineTimestamp(deadline);

                    Map<SongKey, SongInfoDTO> songMap = new HashMap<>();
                    songMap.put(JINGLE_INTRO, new SongInfoDTO(jingle1.getId(), jingleDuration(jingle1)));
                    songMap.put(JINGLE_OUTRO, new SongInfoDTO(jingle2.getId(), jingleDuration(jingle2)));
                    songMap.put(BACKGROUND_MUSIC,   new SongInfoDTO(background.getId(), songDuration(background)));

                    Map<IntroKey, IntroInfoDTO> filePaths = new HashMap<>();
                    filePaths.put(NEWS_BLOCK, new IntroInfoDTO(tts.filePath(), tts.durationSeconds()));

                    dto.setSongs(songMap);
                    dto.setFilePaths(filePaths);

                    return queueSupplier.sendSongsToQueue(brandName, dto, scene.getTraceId());
                });
    }

    private int jingleDuration(SoundFragment jingle) {
        return jingle.getLength() != null ? (int) jingle.getLength().toSeconds() : DEFAULT_JINGLE_DURATION;
    }

    private int songDuration(SoundFragment song) {
        return song.getLength() != null ? (int) song.getLength().toSeconds() : DEFAULT_BACKGROUND_DURATION;
    }
}
