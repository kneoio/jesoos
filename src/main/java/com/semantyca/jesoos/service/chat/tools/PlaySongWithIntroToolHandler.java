package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.manipulation.FFmpegProvider;
import com.semantyca.mixpla.dto.queue.livestream.*;
import com.semantyca.mixpla.model.cnst.MixingType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlaySongWithIntroToolHandler extends BaseToolHandler {

    public static final int MAX_TTS_INTRO_LENGTH = 500;

    public static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> execute(
            Map<String, Object> inputMap,
            AiAgentService aiAgentService, BrandPool brandPool,
            IntroTtsGenerator introTtsGenerator, InternalRestCall internalRestCall,
            UserService userService, JesoosConfig config, FFmpegProvider ffmpegProvider, long userId) {
        String brandName = (String) inputMap.getOrDefault("brandName", "");
        String songIdStr = (String) inputMap.getOrDefault("songId", "");
        String textToTTSIntro = (String) inputMap.getOrDefault("textToTTSIntro", "");
        if (textToTTSIntro == null) textToTTSIntro = "";
        String listenerAudioFilename = ((String) inputMap.getOrDefault("listenerAudioFilename", "")).trim();
        boolean hasListener = !listenerAudioFilename.isEmpty();
        boolean hasText = !textToTTSIntro.isBlank();
        int priority = com.semantyca.mixpla.model.cnst.StreamPriority.GENTLE_INTERRUPT.getValue();
        try { if (inputMap.containsKey("priority")) { int p = ((Number) inputMap.get("priority")).intValue();
            priority = (p == com.semantyca.mixpla.model.cnst.StreamPriority.HARD_INTERRUPT.getValue())
                    ? com.semantyca.mixpla.model.cnst.StreamPriority.HARD_INTERRUPT.getValue() : priority; }
        } catch (Exception ignored) {}
        if (brandName.isEmpty() || songIdStr.isEmpty() || (!hasText && !hasListener)) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Missing required parameters").encode()));
        }
        if (hasText && textToTTSIntro.length() > MAX_TTS_INTRO_LENGTH) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "textToTTSIntro exceeds max length of " + MAX_TTS_INTRO_LENGTH + " characters").encode()));
        }
        UUID songId;
        try { songId = UUID.fromString(songIdStr); } catch (Exception e) {
            return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Invalid songId format").encode()));
        }
        int finalPriority = priority;
        String finalText = textToTTSIntro;
        if (hasListener) {
            return enqueueWithListener(brandName, songId, songIdStr, finalText, hasText, listenerAudioFilename,
                    finalPriority, aiAgentService, brandPool, introTtsGenerator, internalRestCall,
                    userService, config, ffmpegProvider, userId);
        }
        return enqueueTtsOnly(brandName, songId, songIdStr, finalText, finalPriority,
                aiAgentService, brandPool, introTtsGenerator, internalRestCall);
    }

    private static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> enqueueTtsOnly(
            String brandName, UUID songId, String songIdStr, String textToTTSIntro, int priority,
            AiAgentService aiAgentService, BrandPool brandPool,
            IntroTtsGenerator introTtsGenerator, InternalRestCall internalRestCall) {
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
                                            return internalRestCall.addSongToQueue(baseDto(brandName, songId, priority, traceId,
                                                    MixingType.INTRO_SONG, Map.of(IntroKey.INTRO_1, introDto)));
                                        });
                            });
                })
                .map(ignored -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", true).put("brandName", brandName).put("songId", songIdStr)
                                .put("mixingType", MixingType.INTRO_SONG.name()).encode()))
                .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }

    private static Uni<com.semantyca.jesoos.service.chat.ToolNodeResult> enqueueWithListener(
            String brandName, UUID songId, String songIdStr, String textToTTSIntro, boolean hasText,
            String listenerAudioFilename, int priority,
            AiAgentService aiAgentService, BrandPool brandPool,
            IntroTtsGenerator introTtsGenerator, InternalRestCall internalRestCall,
            UserService userService, JesoosConfig config, FFmpegProvider ffmpegProvider, long userId) {
        return userService.get(userId).chain(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "User not found").encode()));
            }
            Path listenerFile = AssessTrackToolHandler.resolveTempFile(config, userOpt.get().getLogin(), listenerAudioFilename);
            if (!Files.isRegularFile(listenerFile)) {
                return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "Listener audio not found: " + listenerAudioFilename).encode()));
            }
            IntroInfoDTO listenerDto = listenerIntroDto(listenerFile, ffmpegProvider);
            MixingType mixingType = hasText ? MixingType.INTRO_LISTENER_SONG : MixingType.LISTENER_SONG;
            return brandPool.get(brandName)
                    .chain(stream -> {
                        if (stream == null) return Uni.createFrom().item(com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Station offline").encode()));
                        UUID traceId = UUID.randomUUID();
                        if (!hasText) {
                            return internalRestCall.addSongToQueue(baseDto(brandName, songId, priority, traceId,
                                    mixingType, Map.of(IntroKey.LISTENER, listenerDto)));
                        }
                        return aiAgentService.getById(stream.getAiAgentId())
                                .chain(agent -> introTtsGenerator.generateCustomIntroAudioFile(textToTTSIntro, agent,
                                        com.semantyca.jesoos.util.AiHelperUtils.selectLanguageByWeight(agent),
                                        "chat-dj-request", traceId, brandName, 0)
                                        .chain(introResult -> {
                                            IntroInfoDTO introDto = new IntroInfoDTO(introResult.filePath(), introResult.durationSeconds());
                                            introDto.setGain(introResult.gain()); introDto.setEngineType(introResult.engineType());
                                            Map<IntroKey, IntroInfoDTO> filePaths = new HashMap<>();
                                            filePaths.put(IntroKey.INTRO_1, introDto);
                                            filePaths.put(IntroKey.LISTENER, listenerDto);
                                            return internalRestCall.addSongToQueue(baseDto(brandName, songId, priority, traceId,
                                                    mixingType, filePaths));
                                        }));
                    })
                    .map(ignored -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                            new JsonObject().put("ok", true).put("brandName", brandName).put("songId", songIdStr)
                                    .put("mixingType", mixingType.name()).encode()))
                    .onFailure().recoverWithItem(err -> com.semantyca.jesoos.service.chat.ToolNodeResult.ok(
                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
        });
    }

    private static SongQueueMessageDTO baseDto(String brandName, UUID songId, int priority, UUID traceId,
                                               MixingType mixingType, Map<IntroKey, IntroInfoDTO> filePaths) {
        SongQueueMessageDTO dto = new SongQueueMessageDTO();
        dto.setMessageId(UUID.randomUUID());
        dto.setTraceId(traceId);
        dto.setTimestamp(System.currentTimeMillis());
        dto.setBrandSlug(brandName);
        dto.setSceneTitle("chat-dj-request");
        dto.setMergingMethod(mixingType);
        dto.setPriority(priority);
        dto.setFilePaths(filePaths);
        dto.setSongs(Map.of(SongKey.SONG_1, new SongInfoDTO(songId, 0)));
        return dto;
    }

    private static IntroInfoDTO listenerIntroDto(Path listenerFile, FFmpegProvider ffmpegProvider) {
        int duration = 10;
        try {
            FFmpegProbeResult probe = ffmpegProvider.getFFprobe().probe(listenerFile.toString());
            if (probe.getFormat() != null && probe.getFormat().duration > 0) {
                duration = (int) Math.ceil(probe.getFormat().duration);
            }
        } catch (Exception ignored) {}
        return new IntroInfoDTO(listenerFile.toAbsolutePath().toString(), duration);
    }
}
