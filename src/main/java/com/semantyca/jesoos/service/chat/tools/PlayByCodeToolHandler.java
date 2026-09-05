package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class PlayByCodeToolHandler extends BaseToolHandler {

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            String sessionBrandSlug,
            SoundFragmentService soundFragmentService,
            BrandPool brandPool,
            InternalRestCall internalRestCall) {
        String brandName = ((String) inputMap.getOrDefault("brandName", "")).trim();
        if (brandName.isEmpty() && sessionBrandSlug != null) {
            brandName = sessionBrandSlug.trim();
        }
        String playCode = normalizePlayCode((String) inputMap.getOrDefault("playCode", ""));
        if (brandName.isEmpty() || playCode == null) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Missing play code").encode()));
        }
        String finalBrand = brandName;
        return soundFragmentService.findByPlayCodeForBrand(playCode, finalBrand)
                .chain(fragment -> {
                    if (fragment == null) {
                        return Uni.createFrom().item(ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Unknown play code").encode()));
                    }
                    return queueSong(finalBrand, fragment, brandPool, internalRestCall);
                });
    }

    private static Uni<ToolNodeResult> queueSong(
            String brandName, SoundFragment fragment, BrandPool brandPool, InternalRestCall internalRestCall) {
        return brandPool.get(brandName)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().item(ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Station offline").encode()));
                    }
                    UUID traceId = UUID.randomUUID();
                    SongQueueMessageDTO dto = new SongQueueMessageDTO();
                    dto.setMessageId(UUID.randomUUID());
                    dto.setTraceId(traceId);
                    dto.setTimestamp(System.currentTimeMillis());
                    dto.setBrandSlug(brandName);
                    dto.setSceneTitle("chat-play-code");
                    dto.setMergingMethod(MixingType.SONG_ONLY);
                    dto.setPriority(StreamPriority.GENTLE_INTERRUPT.getValue());
                    dto.setSongs(Map.of(SongKey.SONG_1, new SongInfoDTO(fragment.getId(), 0)));
                    return internalRestCall.addSongToQueue(dto)
                            .replaceWith(ToolNodeResult.ok(new JsonObject()
                                    .put("ok", true)
                                    .put("brandName", brandName)
                                    .put("songId", fragment.getId().toString())
                                    .put("title", fragment.getTitle())
                                    .put("artist", fragment.getArtist())
                                    .put("mixingType", MixingType.SONG_ONLY.name())
                                    .encode()));
                })
                .onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }

    static String normalizePlayCode(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.startsWith("#")) {
            t = t.substring(1).trim();
        }
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }
}
