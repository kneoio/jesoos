package com.semantyca.djinn.service.live;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.AiDjStatsDTO;
import com.semantyca.djinn.dto.SongPromptDTO;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.model.stream.RadioStream;
import com.semantyca.djinn.service.PromptService;
import com.semantyca.djinn.service.SceneService;
import com.semantyca.djinn.service.live.scripting.DraftFactory;
import com.semantyca.djinn.service.soundfragment.SoundFragmentService;
import com.semantyca.djinn.util.AiHelperUtils;
import com.semantyca.mixpla.model.Prompt;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.cnst.GeneratedContentStatus;
import com.semantyca.mixpla.model.cnst.TTSEngineType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class RadioStreamSupplier extends StreamSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStreamSupplier.class);

    @FunctionalInterface
    public interface MessageSink {
        void add(String stationSlug, AiDjStatsDTO.MessageType type, String message);
    }

    private final PromptService promptService;
    private final DraftFactory draftFactory;
    private final SceneService sceneService;
    private final SoundFragmentService soundFragmentService;
    private final JinglePlaybackHandler jinglePlaybackHandler;

    @Inject
    public RadioStreamSupplier(PromptService promptService, DraftFactory draftFactory, SceneService sceneService, SoundFragmentService soundFragmentService, JinglePlaybackHandler jinglePlaybackHandler) {
        this.promptService = promptService;
        this.draftFactory = draftFactory;
        this.sceneService = sceneService;
        this.soundFragmentService = soundFragmentService;
        this.jinglePlaybackHandler = jinglePlaybackHandler;
    }

    public Uni<Tuple2<List<SongPromptDTO>, String>> fetchStuffForRadioStream(
            RadioStream stream,
            AiAgent agent,
            LanguageTag broadcastingLanguage,
            String additionalInstruction,
            MessageSink messageSink
    ) {
        LiveScene activeScene = stream.findActiveScene(0);
        if (activeScene == null) {
            return Uni.createFrom().failure(
                new IllegalStateException("No active scene found for RadioStream: " + stream.getSlugName())
            );
        }

        UUID activeSceneId = activeScene.getSceneId();
        String currentSceneTitle = activeScene.getSceneTitle();

        if (activeScene.getActualStartTime() == null) {
            activeScene.setActualStartTime(LocalDateTime.now());
        }

        Set<UUID> fetchedSongsInScene = stream.getFetchedSongsInScene(activeSceneId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sceneEndTime = activeScene.getScheduledEndTime();
        long minutesUntilEnd = Duration.between(now, sceneEndTime).toMinutes();
        
        if (minutesUntilEnd < 5 && minutesUntilEnd >= 0) {
            messageSink.add(
                    stream.getSlugName(),
                    AiDjStatsDTO.MessageType.INFO,
                    String.format("Scene '%s' ending in %d minutes - stopping song fetch to allow next scene", currentSceneTitle, minutesUntilEnd)
            );
            return Uni.createFrom().item(() -> null);
        }

        Uni<List<SoundFragment>> songsUni;
        List<PendingSongEntry> scheduledSongs = activeScene.getSongs();

        if (scheduledSongs.isEmpty()) {
            if (activeScene.getSourcing() == WayOfSourcing.GENERATED) {
                songsUni = generateContentForScene(
                    activeScene,
                    stream.getMasterBrand().getId(),
                    soundFragmentService,
                    agent,
                    stream,
                    broadcastingLanguage
                );
            } else {
                messageSink.add(
                        stream.getSlugName(),
                        AiDjStatsDTO.MessageType.ERROR,
                        String.format("Scene '%s' has no predefined songs", currentSceneTitle)
                );
                return Uni.createFrom().item(() -> null);
            }
        } else {
            List<SoundFragment> pickedSongs = pickSongsFromScheduled(scheduledSongs, fetchedSongsInScene);

            if (pickedSongs.isEmpty()) {
                if (LocalDateTime.now().isAfter(activeScene.getScheduledEndTime())) {
                    activeScene.setActualEndTime(LocalDateTime.now());
                    stream.clearSceneState(activeSceneId);
                } else {
                    // Songs exhausted but time remains, wait for next cycle
                    messageSink.add(
                            stream.getSlugName(),
                            AiDjStatsDTO.MessageType.INFO,
                            String.format("Scene '%s' has no more songs but time remains - waiting", currentSceneTitle)
                    );
                }
                return Uni.createFrom().item(() -> null);
            }

            songsUni = Uni.createFrom().item(pickedSongs);
        }

        return songsUni.flatMap(songList -> {
            if (songList == null || songList.isEmpty()) {
                if (activeScene.getSourcing() == WayOfSourcing.GENERATED) {
                    activeScene.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
                    messageSink.add(
                            stream.getSlugName(),
                            AiDjStatsDTO.MessageType.ERROR,
                            String.format("Failed to generate content for scene '%s'", currentSceneTitle)
                    );
                }
                return Uni.createFrom().nullItem();
            }

            return Uni.createFrom().item(songList);
        }).flatMap(songList -> {
            if (songList == null) {
                return Uni.createFrom().nullItem();
            }
            double effectiveTalkativity = activeScene.getTalkativity();
            double rate = stream.getPopularityRate();
            if (rate < 4.0) {
                double factor = Math.max(0.0, Math.min(1.0, rate / 5.0));
                effectiveTalkativity =
                        Math.max(0.0, Math.min(1.0, effectiveTalkativity * factor));
            }

            if (AiHelperUtils.shouldPlayJingle(effectiveTalkativity)) {
                return sceneService.getById(activeScene.getSceneId(), SuperUser.build())
                        .chain(scene -> {
                            jinglePlaybackHandler.handleJinglePlayback(stream, scene, activeScene, fetchedSongsInScene);
                            return Uni.createFrom().item(() -> null);
                        });
            }

            List<UUID> enabledIntroPrompts = activeScene.getIntroPrompts() != null
                    ? activeScene.getIntroPrompts().stream()
                    .filter(ScenePrompt::isActive)
                    .map(ScenePrompt::getPromptId)
                    .toList()
                    : List.of();

            if (enabledIntroPrompts.isEmpty()) {
                messageSink.add(
                        stream.getSlugName(),
                        AiDjStatsDTO.MessageType.INFO,
                        String.format("Scene '%s' has no prompts - queueing songs directly", currentSceneTitle)
                );
                return queueSongsDirectly(stream, songList, fetchedSongsInScene)
                        .map(success -> null);
            }

            List<Uni<Prompt>> promptUnis = enabledIntroPrompts.stream()
                    .map(masterId ->
                            promptService.getById(masterId, SuperUser.build())
                                    .flatMap(masterPrompt -> {
                                        if (masterPrompt.getLanguageTag() == broadcastingLanguage) {
                                            return Uni.createFrom().item(masterPrompt);
                                        }
                                        return promptService
                                                .findByMasterAndLanguage(masterId, broadcastingLanguage, false)
                                                .map(p -> p != null ? p : masterPrompt);
                                    })
                    )
                    .toList();

            return Uni.join().all(promptUnis).andFailFast()
                    .flatMap(prompts -> processSongPromptBatch(
                            songList,
                            prompts,
                            agent,
                            stream,
                            additionalInstruction,
                            activeScene,
                            fetchedSongsInScene,
                            currentSceneTitle,
                            messageSink
                    ));
        });
    }

    private Uni<Tuple2<List<SongPromptDTO>, String>> processSongPromptBatch(
            List<SoundFragment> songList,
            List<Prompt> prompts,
            AiAgent agent,
            RadioStream stream,
            String additionalInstruction,
            LiveScene activeScene,
            Set<UUID> fetchedSongsInScene,
            String currentSceneTitle,
            MessageSink messageSink
    ) {
        Random random = new Random();
        List<Uni<SongPromptDTO>> songPromptUnis = songList.stream()
                .map(song -> {
                    Prompt selectedPrompt;
                    do {
                        selectedPrompt = prompts.get(random.nextInt(prompts.size()));
                    } while (selectedPrompt.isPodcast() && agent.getTtsSetting().getDj().getEngineType() != TTSEngineType.ELEVENLABS);
                    Prompt finalSelectedPrompt = selectedPrompt;
                    return draftFactory.createDraft(
                                    song,
                                    agent,
                                    stream,
                                    selectedPrompt.getDraftId(),
                                    LanguageTag.EN_US,
                                    Map.of()
                            )
                            .map(draft -> new SongPromptDTO(
                                    song.getId(),
                                    draft,
                                    finalSelectedPrompt.getPrompt() + additionalInstruction,
                                    finalSelectedPrompt.getPromptType(),
                                    agent.getLlmType(),
                                    activeScene.getScheduledStartTime().toLocalTime(),
                                    finalSelectedPrompt.isPodcast(),
                                    finalSelectedPrompt.getTitle()
                            ))
                            .onFailure().recoverWithItem(throwable -> {
                                LOGGER.error("Failed to create draft '{}' for song '{}' (ID: {}). Error: {} - {}. Skipping this item.",
                                        finalSelectedPrompt.getDraftId(), song.getTitle(), song.getId(),
                                        throwable.getClass().getSimpleName(), throwable.getMessage(), throwable);
                                messageSink.add(
                                        stream.getSlugName(),
                                        AiDjStatsDTO.MessageType.ERROR,
                                        String.format("Failed to create draft for song '%s': %s", song.getTitle(), throwable.getMessage())
                                );
                                return null;
                            });
                })
                .toList();

        return Uni.join().all(songPromptUnis).andCollectFailures()
                .map(results -> {
                    List<SongPromptDTO> successfulResults = new ArrayList<>();
                    for (int i = 0; i < results.size(); i++) {
                        SongPromptDTO result = results.get(i);
                        if (result != null) {
                            successfulResults.add(result);
                            fetchedSongsInScene.add(songList.get(i).getId());
                        }
                    }

                    if (successfulResults.isEmpty()) {
                        LOGGER.error("All song+draft+prompt combinations failed for scene '{}'", currentSceneTitle);
                        messageSink.add(
                                stream.getSlugName(),
                                AiDjStatsDTO.MessageType.ERROR,
                                String.format("All drafts failed for scene '%s'", currentSceneTitle)
                        );
                        return null;
                    }

                    if (successfulResults.size() < songList.size()) {
                        LOGGER.warn("Partial success: {}/{} drafts created for scene '{}'",
                                successfulResults.size(), songList.size(), currentSceneTitle);
                    }

                    return Tuple2.of(successfulResults, currentSceneTitle);
                });
    }

   private Uni<Boolean> queueSongsDirectly(RadioStream stream, List<SoundFragment> songs, Set<UUID> fetchedSongsInScene) {
      /*  PlaylistManager playlistManager = stream.getStreamManager().getPlaylistManager();
        
        return Multi.createFrom().iterable(songs)
                .onItem().transformToUniAndConcatenate(song -> {
                    List<FileMetadata> metadataList = song.getFileMetadataList();
                    if (metadataList == null || metadataList.isEmpty()) {
                        return Uni.createFrom().item(false);
                    }
                    FileMetadata metadata = metadataList.getFirst();
                    
                    return soundFragmentService.getFileBySlugName(
                                    song.getId(),
                                    metadata.getSlugName(),
                                    SuperUser.build()
                            )
                            .chain(fetchedMetadata -> fetchedMetadata.materializeFileStream(playlistManager.getClass().getName()))
                            .chain(tempFilePath -> {
                                AddToQueueDTO queueDTO = new AddToQueueDTO();
                                queueDTO.setPriority(15);
                                queueDTO.setMergingMethod(MergingType.NOT_MIXED);
                                song.setType(PlaylistItemType.SONG);

                                return playlistManager.addFragmentToSlice(
                                        song,
                                        15,
                                        stream.getBitRate(),
                                        queueDTO
                                ).onItem().invoke(() -> fetchedSongsInScene.add(song.getId()));
                            })
                            .onFailure().recoverWithItem(false);
                })
                .collect().asList()
                .map(results -> results.stream().anyMatch(r -> r));*/
       return Uni.createFrom().item(false);
    }
}
