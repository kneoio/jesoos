package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PromptEntry;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.SongEmitter;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.model.cnst.MixingType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class PlaySongWithIntroToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaySongWithIntroToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            SoundFragmentService soundFragmentService,
            AiAgentService aiAgentService,
            BrandPool brandPool,
            SongEmitter songEmitter,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        PlaySongWithIntroToolHandler handler = new PlaySongWithIntroToolHandler();
        String brandName = (String) inputMap.getOrDefault("brandName", "");
        String songIdStr = (String) inputMap.getOrDefault("songId", "");
        String textToTTSIntro = (String) inputMap.getOrDefault("textToTTSIntro", "");
        int priority = 7;
        try {
            if (inputMap.containsKey("priority")) priority = ((Number) inputMap.get("priority")).intValue();
        } catch (Exception ignored) {}

        if (brandName.isEmpty() || songIdStr.isEmpty() || textToTTSIntro.isEmpty()) {
            return handler.handleError(toolCall, "Missing required parameters", conversationHistory, systemPromptCall2, streamFn);
        }

        UUID songId;
        try {
            songId = UUID.fromString(songIdStr);
        } catch (Exception e) {
            return handler.handleError(toolCall, "Invalid songId format", conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Queueing song...");
        int finalPriority = priority;

        return brandPool.get(brandName)
                .chain(stream -> {
                    if (stream == null) return Uni.createFrom().failure(new RuntimeException("Station offline"));
                    return soundFragmentService.getById(songId)
                            .chain(soundFragment -> {
                                if (soundFragment == null) return Uni.createFrom().failure(new RuntimeException("Song not found"));
                                return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build())
                                        .chain(agent -> {
                                            PromptEntry promptEntry = new PromptEntry();
                                            promptEntry.setPromptId(UUID.randomUUID());
                                            LanguageCode primaryLang = agent.getPreferredLang().getFirst().getLanguageTag().toLanguageCode();
                                            promptEntry.setLanguage(primaryLang);
                                            SongEntry songEntry = new SongEntry(soundFragment, promptEntry, 0);
                                            TimelineEntry entry = new TimelineEntry(0, LocalDateTime.now(), List.of(songEntry), MixingType.INTRO_SONG, true, false);
                                            LiveScene liveScene = new LiveScene();
                                            liveScene.setSceneId(UUID.randomUUID());
                                            liveScene.setSceneTitle("chat-dj-request");
                                            liveScene.setTimeZone(stream.getTimeZone());
                                            liveScene.setTraceId(UUID.randomUUID());
                                            liveScene.setTimeline(List.of(entry));
                                            return songEmitter.sendWithCustomIntro(brandName, liveScene, entry, textToTTSIntro, agent, stream.getTimeZone(), finalPriority);
                                        });
                            });
                })
                .flatMap(result -> {
                    JsonObject payload = new JsonObject().put("ok", true).put("brandName", brandName).put("songId", songIdStr);
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Song queued!");
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[PlaySongWithIntro] Failed", err);
                    JsonObject errorPayload = new JsonObject().put("ok", false).put("error", err.getMessage());
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                });
    }

    private Uni<Void> handleError(LlmToolCall toolCall, String errorMessage,
                                   List<LlmMessage> conversationHistory, String systemPromptCall2,
                                   Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject errorPayload = new JsonObject().put("ok", false).put("error", errorMessage);
        addToolUseToHistory(toolCall, conversationHistory);
        addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
        return streamFn.apply(buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
