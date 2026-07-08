package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.model.cnst.MixingType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.UUID;

public class PlaySongWithIntroToolHandler extends BaseToolHandler {

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
                    return aiAgentService.getById(stream.getAiAgentId())
                            .chain(agent -> {
                                UUID traceId = UUID.randomUUID();
                                return introTtsGenerator.generateCustomIntroAudioFile(textToTTSIntro, agent,
                                        com.semantyca.jesoos.util.AiHelperUtils.selectLanguageByWeight(agent), "chat-dj-request", traceId, brandName, 0)
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
