package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            AiAgentService aiAgentService,
            BrandPool brandPool,
            IntroTtsGenerator introTtsGenerator,
            InternalRestCall internalRestCall,
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
        int priority = StreamPriority.GENTLE_INTERRUPT.getValue();
        try {
            if (inputMap.containsKey("priority")) {
                int p = ((Number) inputMap.get("priority")).intValue();
                priority = (p == StreamPriority.HARD_INTERRUPT.getValue()) ? StreamPriority.HARD_INTERRUPT.getValue() : StreamPriority.GENTLE_INTERRUPT.getValue();
            }
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
                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build())
                            .chain(agent -> {
                                UUID traceId = UUID.randomUUID();
                                return introTtsGenerator.generateCustomIntroAudioFile(
                                        textToTTSIntro,
                                        agent,
                                        AiHelperUtils.selectLanguageByWeight(agent),
                                        "chat-dj-request",
                                        traceId,
                                        brandName
                                ).chain(introResult -> {
                                    IntroInfoDTO introDto = new IntroInfoDTO(introResult.filePath(), introResult.durationSeconds());
                                    introDto.setGain(introResult.gain());
                                    introDto.setEngineType(introResult.engineType());

                                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                                    dto.setMessageId(UUID.randomUUID());
                                    dto.setTraceId(traceId);
                                    dto.setTimestamp(System.currentTimeMillis());
                                    dto.setBrandSlug(brandName);
                                    dto.setSceneTitle("chat-dj-request");
                                    dto.setMergingMethod(MixingType.INTRO_SONG);
                                    dto.setPriority(finalPriority);
                                    dto.setFilePaths(Map.of(IntroKey.INTRO_1, introDto));
                                    dto.setSongs(Map.of(SongKey.SONG_1, new SongInfoDTO(songId, 0)));

                                    return internalRestCall.addSongToQueue(dto);
                                });
                            });
                })
                .chain(ignored -> {
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

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap,
            AiAgentService aiAgentService, BrandPool brandPool,
            IntroTtsGenerator introTtsGenerator, InternalRestCall internalRestCall) {
        String brandName = (String) inputMap.getOrDefault("brandName", "");
        String songIdStr = (String) inputMap.getOrDefault("songId", "");
        String textToTTSIntro = (String) inputMap.getOrDefault("textToTTSIntro", "");
        int priority = com.semantyca.mixpla.model.cnst.StreamPriority.GENTLE_INTERRUPT.getValue();
        try { if (inputMap.containsKey("priority")) { int p = ((Number) inputMap.get("priority")).intValue();
            priority = (p == com.semantyca.mixpla.model.cnst.StreamPriority.HARD_INTERRUPT.getValue())
                    ? com.semantyca.mixpla.model.cnst.StreamPriority.HARD_INTERRUPT.getValue() : priority; }
        } catch (Exception ignored) {}
        if (brandName.isEmpty() || songIdStr.isEmpty() || textToTTSIntro.isEmpty()) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Missing required parameters").encode()));
        }
        UUID songId;
        try { songId = UUID.fromString(songIdStr); } catch (Exception e) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Invalid songId format").encode()));
        }
        int finalPriority = priority;
        return brandPool.get(brandName)
                .chain(stream -> {
                    if (stream == null) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", "Station offline").encode()));
                    return aiAgentService.getById(stream.getAiAgentId(), new com.semantyca.core.model.user.SuperUser())
                            .chain(agent -> {
                                UUID traceId = UUID.randomUUID();
                                return introTtsGenerator.generateCustomIntroAudioFile(textToTTSIntro, agent,
                                        com.semantyca.jesoos.util.AiHelperUtils.selectLanguageByWeight(agent), "chat-dj-request", traceId, brandName)
                                        .chain(introResult -> {
                                            IntroInfoDTO introDto = new IntroInfoDTO(introResult.filePath(), introResult.durationSeconds());
                                            introDto.setGain(introResult.gain()); introDto.setEngineType(introResult.engineType());
                                            SongQueueMessageDTO dto = new SongQueueMessageDTO();
                                            dto.setMessageId(UUID.randomUUID()); dto.setTraceId(traceId);
                                            dto.setTimestamp(System.currentTimeMillis()); dto.setBrandSlug(brandName);
                                            dto.setSceneTitle("chat-dj-request"); dto.setMergingMethod(MixingType.INTRO_SONG);
                                            dto.setPriority(finalPriority);
                                            dto.setFilePaths(Map.of(IntroKey.INTRO_1, introDto));
                                            dto.setSongs(Map.of(SongKey.SONG_1, new SongInfoDTO(songId, 0)));
                                            return internalRestCall.addSongToQueue(dto);
                                        });
                            });
                })
                .map(ignored -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", true).put("brandName", brandName).put("songId", songIdStr).encode()))
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
