package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PromptEntry;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.ListenerService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class UploadSongToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(UploadSongToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            UserService userService,
            SoundFragmentService soundFragmentService,
            AiHelperService aiHelperService,
            BrandPool brandPool,
            SongEmitter songEmitter,
            AiAgentService aiAgentService,
            ListenerLabelCache labelCache,
            String brandName,
            long userId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        UploadSongToolHandler handler = new UploadSongToolHandler();

        String tempFilename = inputMap.getOrDefault("temp_filename", JsonValue.from("")).toString().replace("\"", "");
        String title = inputMap.getOrDefault("title", JsonValue.from("")).toString().replace("\"", "");
        String artist = inputMap.getOrDefault("artist", JsonValue.from("")).toString().replace("\"", "");
        String description = inputMap.getOrDefault("description", JsonValue.from("")).toString().replace("\"", "");
        String introText = inputMap.getOrDefault("intro_text", JsonValue.from("")).toString().replace("\"", "");

        List<String> genreNames = List.of();
        try {
            if (inputMap.containsKey("genre_names")) {
                var node = new ObjectMapper().readTree(inputMap.get("genre_names").toString());
                if (node.isArray()) {
                    genreNames = new java.util.ArrayList<>();
                    for (var n : node) genreNames.add(n.asText());
                }
            }
        } catch (Exception ignored) {}

        if (tempFilename.isEmpty() || title.isEmpty() || artist.isEmpty()) {
            LOGGER.warnf("[UploadSong] Missing required fields — temp_filename='%s', title='%s', artist='%s'", tempFilename, title, artist);
            return handler.error(toolUse, "temp_filename, title and artist are required", conversationHistory, systemPromptCall2, streamFn);
        }

        final List<String> finalGenreNames = genreNames;

        return listenerService.getByUserId(userId)
                .flatMap(listener -> {
                    if (listener == null) {
                        return handler.error(toolUse, "Listener not found.", conversationHistory, systemPromptCall2, streamFn);
                    }
                    java.util.UUID artistLabelId = labelCache.get("artist");
                    boolean isArtist = artistLabelId != null
                            && listener.getLabels() != null
                            && listener.getLabels().contains(artistLabelId);
                    LOGGER.infof("[UploadSong] artistLabelId=%s, listenerLabels=%s, isArtist=%s", artistLabelId, listener.getLabels(), isArtist);
                    if (!isArtist) {
                        return handler.error(toolUse,
                                "Listener profile does not have the station 'artist' label yet (not the same as email or sign-in). If the user wants to upload, call listener_data add_label with label_identifier=artist after they confirm, then call upload_song again.",
                                conversationHistory, systemPromptCall2, streamFn);
                    }

                    return userService.get(userId).flatMap(userOpt -> {
                        if (userOpt.isEmpty()) {
                            return handler.error(toolUse, "User not found.", conversationHistory, systemPromptCall2, streamFn);
                        }
                        IUser user = userOpt.get();

                        return aiHelperService.resolveGenreNamesToIds(finalGenreNames)
                                .flatMap(genreIds -> {
                                    SoundFragmentDTO dto = new SoundFragmentDTO();
                                    dto.setTitle(title);
                                    dto.setArtist(artist);
                                    dto.setType(PlaylistItemType.SONG);
                                    dto.setSource(SourceType.CONTRIBUTION);
                                    dto.setGenres(genreIds.isEmpty() ? List.of() : genreIds);
                                    dto.setDescription(description.isBlank() ? null : description);
                                    dto.setNewlyUploaded(List.of(tempFilename));

                                    return brandPool.get(brandName).flatMap(stream -> {
                                        if (stream == null) {
                                            return handler.error(toolUse, "Station not active.", conversationHistory, systemPromptCall2, streamFn);
                                        }

                                        dto.setRepresentedInBrands(List.of(stream.getId()));

                                        LOGGER.infof("[UploadSong] Attempting upsert — file='%s', title='%s', artist='%s', genres=%s", tempFilename, title, artist, finalGenreNames);
                                    handler.sendProcessingChunk(chunkHandler, connectionId, "Saving your track...");

                                        // TODO: Shazam copyright check placeholder

                                        return soundFragmentService.upsert("new", dto, user, LanguageCode.en)
                                                .flatMap(saved -> {
                                                    UUID songId = saved.getId();
                                                    LOGGER.infof("[UploadSong] Saved contribution '%s' by '%s' id=%s", title, artist, songId);

                                                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build())
                                                            .flatMap(agent -> {
                                                                PromptEntry promptEntry = new PromptEntry();
                                                                promptEntry.setPromptId(UUID.randomUUID());
                                                                promptEntry.setLanguage(LanguageCode.en);

                                                                return soundFragmentService.getById(songId)
                                                                        .flatMap(soundFragment -> {
                                                                            SongEntry songEntry = new SongEntry(soundFragment, promptEntry, 0);
                                                                            TimelineEntry entry = new TimelineEntry(
                                                                                    0, LocalDateTime.now(),
                                                                                    List.of(songEntry),
                                                                                    MixingType.INTRO_SONG, true, false
                                                                            );
                                                                            LiveScene liveScene = new LiveScene();
                                                                            liveScene.setSceneId(UUID.randomUUID());
                                                                            liveScene.setSceneTitle("chat-artist-contribution");
                                                                            liveScene.setTimeZone(stream.getTimeZone());
                                                                            liveScene.setTraceId(UUID.randomUUID());
                                                                            liveScene.setTimeline(List.of(entry));

                                                                            return songEmitter.sendWithCustomIntro(
                                                                                    brandName, liveScene, entry,
                                                                                    introText.isBlank() ? "A fresh track just arrived — " + title + " by " + artist + "!" : introText,
                                                                                    agent, stream.getTimeZone(), 8
                                                                            );
                                                                        });
                                                            })
                                                            .onFailure().recoverWithItem(err -> {
                                                                LOGGER.warnf("[UploadSong] Song saved (id=%s) but broadcast scheduling failed: %s", songId, err.getMessage());
                                                                return null;
                                                            })
                                                            .flatMap(broadcastResult -> {
                                                                JsonObject payload = new JsonObject()
                                                                        .put("ok", true)
                                                                        .put("song_id", songId.toString())
                                                                        .put("title", title)
                                                                        .put("artist", artist)
                                                                        .put("queued", broadcastResult != null);

                                                                handler.addToolUseToHistory(toolUse, conversationHistory);
                                                                handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);
                                                                MessageCreateParams p = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                                                                return streamFn.apply(p);
                                                            });
                                                })
                                                .onFailure().recoverWithUni(err -> {
                                                    LOGGER.error("[UploadSong] Failed", err);
                                                    return handler.error(toolUse, "Failed to save song: " + err.getMessage(), conversationHistory, systemPromptCall2, streamFn);
                                                });
                                    });
                                });
                    });
                });
    }

    private Uni<Void> error(ToolUseBlock toolUse, String message,
                             List<MessageParam> history, String followUp,
                             Function<MessageCreateParams, Uni<Void>> streamFn) {
        JsonObject payload = new JsonObject().put("ok", false).put("error", message);
        addToolUseToHistory(toolUse, history);
        addToolResultToHistory(toolUse, payload.encode(), history);
        return streamFn.apply(buildFollowUpParams(followUp, history));
    }
}
