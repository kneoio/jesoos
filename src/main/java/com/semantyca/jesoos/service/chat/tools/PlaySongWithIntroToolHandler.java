package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PromptEntry;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.SongEmitter;
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
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            SoundFragmentService soundFragmentService,
            AiAgentService aiAgentService,
            BrandPool brandPool,
            SongEmitter songEmitter,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        PlaySongWithIntroToolHandler handler = new PlaySongWithIntroToolHandler();

        String brandName = inputMap.getOrDefault("brandName", JsonValue.from("")).toString().replace("\"", "");
        String songIdStr = inputMap.getOrDefault("songId", JsonValue.from("")).toString().replace("\"", "");
        String textToTTSIntro = inputMap.getOrDefault("textToTTSIntro", JsonValue.from("")).toString().replace("\"", "");

        int priority = 8;
        try {
            if (inputMap.containsKey("priority")) {
                priority = Integer.parseInt(inputMap.get("priority").toString());
            }
        } catch (Exception ignored) {}

        if (brandName.isEmpty() || songIdStr.isEmpty() || textToTTSIntro.isEmpty()) {
            return handler.handleError(toolUse, "Missing required parameters", conversationHistory, systemPromptCall2, streamFn);
        }

        UUID songId;
        try {
            songId = UUID.fromString(songIdStr);
        } catch (Exception e) {
            return handler.handleError(toolUse, "Invalid songId format", conversationHistory, systemPromptCall2, streamFn);
        }

        LOGGER.info("[PlaySongWithIntro] brandName: '{}', songId: {}, intro: '{}'", brandName, songId, textToTTSIntro);

        handler.sendProcessingChunk(chunkHandler, connectionId, "Queueing song...");

        int finalPriority = priority;

        return brandPool.get(brandName)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(new RuntimeException("Station offline"));
                    }

                    return soundFragmentService.getById(songId)
                            .chain(soundFragment -> {
                                if (soundFragment == null) {
                                    return Uni.createFrom().failure(new RuntimeException("Song not found"));
                                }

                                return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                        .chain(agent -> {
                                            LanguageCode primaryLang = agent.getPreferredLang().stream()
                                                    .sorted(java.util.Comparator.comparingDouble(LanguagePreference::getWeight).reversed())
                                                    .map(LanguagePreference::getLanguageTag)
                                                    .findFirst()
                                                    .orElse(LanguageCode.en);

                                            PromptEntry promptEntry = new PromptEntry();
                                            promptEntry.setPromptId(UUID.randomUUID());
                                            promptEntry.setLanguage(primaryLang);

                                            SongEntry songEntry = new SongEntry(soundFragment, promptEntry, 0);

                                            TimelineEntry entry = new TimelineEntry(
                                                    0,
                                                    LocalDateTime.now(),
                                                    List.of(songEntry),
                                                    MixingType.INTRO_SONG,
                                                    true,
                                                    false
                                            );

                                            LiveScene liveScene = new LiveScene();
                                            liveScene.setSceneId(UUID.randomUUID());
                                            liveScene.setSceneTitle("chat-dj-request");
                                            liveScene.setTimeZone(stream.getTimeZone());
                                            liveScene.setTraceId(UUID.randomUUID());
                                            liveScene.setTimeline(List.of(entry));

                                            return songEmitter.sendWithCustomIntro(
                                                    brandName,
                                                    liveScene,
                                                    entry,
                                                    textToTTSIntro,
                                                    agent,
                                                    stream.getTimeZone(),
                                                    finalPriority
                                            );
                                        });
                            });
                })
                .flatMap(result -> {
                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("brandName", brandName)
                            .put("songId", songIdStr);

                    handler.sendProcessingChunk(chunkHandler, connectionId, "Song queued!");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[PlaySongWithIntro] Failed", err);

                    JsonObject errorPayload = new JsonObject()
                            .put("ok", false)
                            .put("error", err.getMessage());

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                });
    }

    private Uni<Void> handleError(
            ToolUseBlock toolUse,
            String errorMessage,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        JsonObject errorPayload = new JsonObject()
                .put("ok", false)
                .put("error", errorMessage);

        addToolUseToHistory(toolUse, conversationHistory);
        addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

        MessageCreateParams secondCallParams = buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(secondCallParams);
    }
}
