package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LifecycleStatus;
import com.semantyca.core.model.cnst.RlsActionType;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.RlsActionDTO;
import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PromptEntry;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.SongEmitter;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class UploadSongToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(UploadSongToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            ListenerService listenerService,
            UserService userService,
            SoundFragmentService soundFragmentService,
            AiHelperService aiHelperService,
            BrandPool brandPool,
            SongEmitter songEmitter,
            AiAgentService aiAgentService,
            ListenerLabelCache labelCache,
            BrandService brandService,
            String brandName,
            long userId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        UploadSongToolHandler handler = new UploadSongToolHandler();
        String tempFilename = (String) inputMap.getOrDefault("temp_filename", "");
        String title = (String) inputMap.getOrDefault("title", "");
        String artist = (String) inputMap.getOrDefault("artist", "");
        String description = (String) inputMap.getOrDefault("description", "");
        String introText = (String) inputMap.getOrDefault("intro_text", "");

        List<String> genreNames = new ArrayList<>();
        if (inputMap.containsKey("genre_names") && inputMap.get("genre_names") instanceof List<?> list) {
            list.forEach(g -> genreNames.add(g.toString()));
        }

        if (tempFilename.isEmpty() || title.isEmpty() || artist.isEmpty()) {
            return handler.error(toolCall, "temp_filename, title and artist are required", conversationHistory, systemPromptCall2, streamFn);
        }

        return listenerService.getByUserId(userId)
                .flatMap(listener -> {
                    if (listener == null) {
                        return handler.error(toolCall, "Listener not found.", conversationHistory, systemPromptCall2, streamFn);
                    }
                    UUID artistLabelId = labelCache.get("artist");
                    boolean isArtist = artistLabelId != null && listener.getLabels() != null && listener.getLabels().contains(artistLabelId);
                    if (!isArtist) {
                        return handler.error(toolCall,
                                "Listener profile does not have the station 'artist' label yet. If the user wants to upload, call listener_data add_label with label_identifier=artist after they confirm, then call upload_song again.",
                                conversationHistory, systemPromptCall2, streamFn);
                    }

                    return userService.get(userId).flatMap(userOpt -> {
                        if (userOpt.isEmpty()) return handler.error(toolCall, "User not found.", conversationHistory, systemPromptCall2, streamFn);
                        IUser user = userOpt.get();

                        return aiHelperService.resolveGenreNamesToIds(genreNames)
                                .flatMap(genreIds -> {
                                    SoundFragmentDTO dto = new SoundFragmentDTO();
                                    dto.setTitle(title);
                                    dto.setArtist(artist);
                                    dto.setType(PlaylistItemType.SONG);
                                    dto.setSource(SourceType.CONTRIBUTION);
                                    dto.setStatus(LifecycleStatus.NOT_APPROVED.getCode());
                                    dto.setGenres(genreIds.isEmpty() ? List.of() : genreIds);
                                    dto.setDescription(description.isBlank() ? null : description);
                                    dto.setNewlyUploaded(List.of(tempFilename));
                                    RlsActionDTO ownerAccess = new RlsActionDTO();
                                    ownerAccess.setAction(RlsActionType.GRANT);
                                    ownerAccess.setUserId(userId);
                                    ownerAccess.setCanEdit(true);
                                    ownerAccess.setCanDelete(true);
                                    dto.getRlsActions().add(ownerAccess);

                                    return brandPool.get(brandName).flatMap(stream -> {
                                        Uni<UUID> brandIdUni = stream != null
                                                ? Uni.createFrom().item(stream.getMasterBrandId())
                                                : brandService.getBySlugName(brandName).map(brand -> brand != null ? brand.getId() : null);

                                        return brandIdUni.flatMap(brandId -> {
                                            if (brandId != null) dto.setRepresentedInBrands(List.of(brandId));
                                            handler.sendProcessingChunk(chunkHandler, connectionId, "Saving your track...");

                                            return soundFragmentService.upsert("new", dto, user, LanguageCode.en)
                                                    .flatMap(saved -> {
                                                        UUID songId = saved.getId();
                                                        if (stream == null) {
                                                            JsonObject payload = new JsonObject().put("ok", true)
                                                                    .put("song_id", songId.toString()).put("title", title)
                                                                    .put("artist", artist).put("queued", false);
                                                            handler.addToolUseToHistory(toolCall, conversationHistory);
                                                            handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                                                            return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                                                        }

                                                        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build())
                                                                .flatMap(agent -> {
                                                                    LanguageCode primaryLang = agent.getPreferredLang().getFirst().getLanguageTag().toLanguageCode();
                                                                    PromptEntry promptEntry = new PromptEntry();
                                                                    promptEntry.setPromptId(UUID.randomUUID());
                                                                    promptEntry.setLanguage(primaryLang);
                                                                    return soundFragmentService.getById(songId)
                                                                            .flatMap(soundFragment -> {
                                                                                SongEntry songEntry = new SongEntry(soundFragment, promptEntry, 0);
                                                                                TimelineEntry entry = new TimelineEntry(0, LocalDateTime.now(), List.of(songEntry), MixingType.INTRO_SONG, true, false);
                                                                                LiveScene liveScene = new LiveScene();
                                                                                liveScene.setSceneId(UUID.randomUUID());
                                                                                liveScene.setSceneTitle("chat-artist-contribution");
                                                                                liveScene.setTimeZone(stream.getTimeZone());
                                                                                liveScene.setTraceId(UUID.randomUUID());
                                                                                liveScene.setTimeline(List.of(entry));
                                                                                return songEmitter.sendWithCustomIntro(brandName, liveScene, entry,
                                                                                        introText.isBlank() ? "A fresh track just arrived — " + title + " by " + artist + "!" : introText,
                                                                                        agent, stream.getTimeZone(), 8);
                                                                            });
                                                                })
                                                                .onFailure().recoverWithItem(err -> null)
                                                                .flatMap(broadcastResult -> {
                                                                    JsonObject payload = new JsonObject().put("ok", true)
                                                                            .put("song_id", songId.toString()).put("title", title)
                                                                            .put("artist", artist).put("queued", broadcastResult != null);
                                                                    handler.addToolUseToHistory(toolCall, conversationHistory);
                                                                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                                                                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                                                                });
                                                    })
                                                    .onFailure().recoverWithUni(err -> {
                                                        LOGGER.error("[UploadSong] Failed", err);
                                                        return handler.error(toolCall, "Failed to save song: " + err.getMessage(), conversationHistory, systemPromptCall2, streamFn);
                                                    });
                                        });
                                    });
                                });
                    });
                });
    }

    private Uni<Void> error(LlmToolCall toolCall, String message, List<LlmMessage> history, String followUp,
                             Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject payload = new JsonObject().put("ok", false).put("error", message);
        addToolUseToHistory(toolCall, history);
        addToolResultToHistory(toolCall, payload.encode(), history);
        return streamFn.apply(buildFollowUpParams(followUp, history));
    }

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap,
            ListenerService listenerService, UserService userService,
            SoundFragmentService soundFragmentService, AiHelperService aiHelperService,
            BrandPool brandPool, SongEmitter songEmitter, AiAgentService aiAgentService,
            ListenerLabelCache labelCache, BrandService brandService,
            String brandName, long userId) {
        UploadSongToolHandler h = new UploadSongToolHandler();
        String tempFilename = (String) inputMap.getOrDefault("temp_filename", "");
        String title = (String) inputMap.getOrDefault("title", "");
        String artist = (String) inputMap.getOrDefault("artist", "");
        String description = (String) inputMap.getOrDefault("description", "");
        String introText = (String) inputMap.getOrDefault("intro_text", "");
        List<String> genreNames = new ArrayList<>();
        if (inputMap.containsKey("genre_names") && inputMap.get("genre_names") instanceof List<?> list)
            list.forEach(g -> genreNames.add(g.toString()));
        if (tempFilename.isEmpty() || title.isEmpty() || artist.isEmpty()) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "temp_filename, title and artist are required").encode()));
        }
        return listenerService.getByUserId(userId)
                .chain(listener -> {
                    if (listener == null) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", "Listener not found").encode()));
                    UUID artistLabelId = labelCache.get("artist");
                    if (artistLabelId == null || listener.getLabels() == null || !listener.getLabels().contains(artistLabelId)) {
                        return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Artist label required. Call listener_data add_label with label_identifier=artist first.").encode()));
                    }
                    return userService.get(userId).chain(userOpt -> {
                        if (userOpt.isEmpty()) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "User not found").encode()));
                        IUser user = userOpt.get();
                        return aiHelperService.resolveGenreNamesToIds(genreNames)
                                .chain(genreIds -> {
                                    SoundFragmentDTO dto = new SoundFragmentDTO();
                                    dto.setTitle(title); dto.setArtist(artist); dto.setDescription(description.isBlank() ? null : description);
                                    dto.setGenres(genreIds.isEmpty() ? List.of() : genreIds);
                                    dto.setSource(SourceType.CONTRIBUTION);
                                    dto.setStatus(LifecycleStatus.NOT_APPROVED.getCode());
                                    dto.setNewlyUploaded(List.of(tempFilename));
                                    dto.setType(PlaylistItemType.SONG);
                                    RlsActionDTO rls = new RlsActionDTO();
                                    rls.setAction(RlsActionType.GRANT); rls.setUserId(userId);
                                    rls.setCanEdit(true); rls.setCanDelete(true);
                                    dto.getRlsActions().add(rls);
                                    return brandPool.get(brandName)
                                            .chain(stream -> {
                                                Uni<UUID> brandIdUni = stream != null
                                                        ? Uni.createFrom().item(stream.getMasterBrandId())
                                                        : brandService.getBySlugName(brandName).map(brand -> brand != null ? brand.getId() : null);
                                                return brandIdUni.chain(brandId -> {
                                                    if (brandId != null) dto.setRepresentedInBrands(List.of(brandId));
                                                    return soundFragmentService.upsert("new", dto, user, com.semantyca.core.model.cnst.LanguageCode.en);
                                                });
                                            })
                                            .map(saved -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                                    new JsonObject().put("ok", true).put("song_id", saved.getId().toString())
                                                            .put("title", title).put("queued", false).encode()));
                                });
                    });
                })
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
