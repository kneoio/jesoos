package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.model.stream.OneTimeStream;
import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.live.OneTimeStreamPool;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.model.cnst.MixingType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.UUID;

/**
 * OTS-scoped counterpart of {@link PlaySongWithIntroToolHandler}. Resolves the live stream from
 * {@link OneTimeStreamPool} (an OTS is never in the brand pool) and routes the injected song on the
 * OTS's own slug — both {@code brandSlug} (the routing key, per knowledge bundle
 * {@code workflows/ots-internals.md}) and {@code otsSlugName}
 * (the tag emissions carry). DJ is always on for an OTS, so no DJ-toggle check.
 */
public class PlaySongForOtsToolHandler extends BaseToolHandler {

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap,
            String otsSlug,
            AiAgentService aiAgentService, OneTimeStreamPool oneTimeStreamPool,
            IntroTtsGenerator introTtsGenerator, InternalRestCall internalRestCall) {
        String songIdStr = (String) inputMap.getOrDefault("songId", "");
        String textToTTSIntro = (String) inputMap.getOrDefault("textToTTSIntro", "");
        int priority = com.semantyca.mixpla.model.cnst.StreamPriority.GENTLE_INTERRUPT.getValue();
        try { if (inputMap.containsKey("priority")) { int p = ((Number) inputMap.get("priority")).intValue();
            priority = (p == com.semantyca.mixpla.model.cnst.StreamPriority.HARD_INTERRUPT.getValue())
                    ? com.semantyca.mixpla.model.cnst.StreamPriority.HARD_INTERRUPT.getValue() : priority; }
        } catch (Exception ignored) {}
        if (otsSlug == null || otsSlug.isEmpty() || songIdStr.isEmpty() || textToTTSIntro.isEmpty()) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Missing required parameters").encode()));
        }
        UUID songId;
        try { songId = UUID.fromString(songIdStr); } catch (Exception e) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Invalid songId format").encode()));
        }
        int finalPriority = priority;
        return oneTimeStreamPool.get(otsSlug)
                .chain(stream -> {
                    if (stream == null) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", "Event stream offline").encode()));
                    return aiAgentService.getById(stream.getAiAgentId())
                            .chain(agent -> {
                                UUID traceId = UUID.randomUUID();
                                return introTtsGenerator.generateCustomIntroAudioFile(textToTTSIntro, agent,
                                        com.semantyca.jesoos.util.AiHelperUtils.selectLanguageByWeight(agent), "chat-ots-request", traceId, otsSlug, 0)
                                        .chain(introResult -> {
                                            IntroInfoDTO introDto = new IntroInfoDTO(introResult.filePath(), introResult.durationSeconds());
                                            introDto.setGain(introResult.gain()); introDto.setEngineType(introResult.engineType());
                                            SongQueueMessageDTO dto = new SongQueueMessageDTO();
                                            dto.setMessageId(UUID.randomUUID()); dto.setTraceId(traceId);
                                            dto.setTimestamp(System.currentTimeMillis());
                                            dto.setBrandSlug(otsSlug);      // routing key = OTS slug
                                            dto.setOtsSlugName(otsSlug);    // tag emissions carry
                                            dto.setSceneTitle("chat-ots-request"); dto.setMergingMethod(MixingType.INTRO_SONG);
                                            dto.setPriority(finalPriority);
                                            dto.setFilePaths(Map.of(IntroKey.INTRO_1, introDto));
                                            dto.setSongs(Map.of(SongKey.SONG_1, new SongInfoDTO(songId, 0)));
                                            return internalRestCall.addSongToQueue(dto);
                                        });
                            })
                            .map(ignored -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                    new JsonObject().put("ok", true).put("otsSlug", otsSlug).put("songId", songIdStr).encode()));
                })
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }
}
