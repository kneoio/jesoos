package com.semantyca.djinn.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.service.live.generated.GeneratedNewsService;
import com.semantyca.djinn.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.GeneratedContentStatus;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public abstract class StreamSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamSupplier.class);

    @Inject
    GeneratedNewsService generatedNewsService;


    protected List<SoundFragment> pickSongsFromScheduled(
            List<PendingSongEntry> scheduledSongs,
            Set<UUID> excludeIds
    ) {
        List<PendingSongEntry> available = scheduledSongs.stream()
                .filter(e -> !excludeIds.contains(e.getSoundFragment().getId()))
                .toList();

        if (available.isEmpty()) {
            return List.of();
        }

        int take = available.size() >= 2 && new Random().nextDouble() < 0.6 ? 2 : 1;
        //int take = 1;
        return available.stream()
                .limit(take)
                .map(PendingSongEntry::getSoundFragment)
                .toList();
    }

    protected Uni<List<SoundFragment>> generateContentForScene(
            LiveScene liveScene,
            UUID brandId,
            SoundFragmentService soundFragmentService,
            AiAgent agent,
            IStream stream,
            LanguageTag airLanguage
    ) {
        liveScene.setGeneratedContentStatus(GeneratedContentStatus.PROCESSING);
        List<ScenePrompt> contentPrompts = liveScene.getContentPrompts();
        UUID promptId = contentPrompts.getFirst().getPromptId();
        
        return generatedNewsService.findOrGenerateFragment(promptId, agent, stream, liveScene, airLanguage)
                .map(fragment -> {
                    if (liveScene.getGeneratedContentStatus() != GeneratedContentStatus.GENERATED) {
                        liveScene.setGeneratedContentStatus(GeneratedContentStatus.REUSING);
                    }
                    if (liveScene.isOneTimeRun()) {
                        liveScene.setLastRunDate(java.time.LocalDateTime.now());
                        LOGGER.info("One-time scene '{}' marked as run at {}", liveScene.getSceneTitle(), liveScene.getLastRunDate());
                    }
                    return List.of(fragment);
                })
                .onFailure().recoverWithUni(error -> {
                    LOGGER.error("Failed to generate content for prompt {}", promptId, error);
                    liveScene.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
                    return Uni.createFrom().item(List.of());
                });
    }
}
