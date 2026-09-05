package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.outbound.InternalRestCall;
import com.semantyca.jesoos.service.AiAgentService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.IntroTtsGenerator;
import com.semantyca.jesoos.service.soundfragment.SoundFragmentService;
import com.semantyca.jesoos.util.AiHelperUtils;
import com.semantyca.mixpla.dto.queue.livestream.IntroInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.IntroKey;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class PlayByCodeToolHandler extends BaseToolHandler {

    private static final String INTROS_RESOURCE = "play_code_intros.json";
    private static final List<String> FALLBACK_INTROS = List.of(
            "This one is for {name} — {title} by {artist}."
    );
    private static final Map<LanguageCode, List<String>> INTROS = loadIntros();

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            String sessionBrandSlug,
            long userId,
            SoundFragmentService soundFragmentService,
            ListenerService listenerService,
            BrandPool brandPool,
            AiAgentService aiAgentService,
            IntroTtsGenerator introTtsGenerator,
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
                    if (userId <= 0) {
                        return queueSong(finalBrand, fragment, null, brandPool, aiAgentService,
                                introTtsGenerator, internalRestCall);
                    }
                    return listenerService.resolveDisplayName(userId, null)
                            .onFailure().recoverWithItem((String) null)
                            .chain(name -> queueSong(finalBrand, fragment, blankToNull(name), brandPool,
                                    aiAgentService, introTtsGenerator, internalRestCall));
                });
    }

    private static Uni<ToolNodeResult> queueSong(
            String brandName, SoundFragment fragment, String listenerName,
            BrandPool brandPool, AiAgentService aiAgentService,
            IntroTtsGenerator introTtsGenerator, InternalRestCall internalRestCall) {
        return brandPool.get(brandName)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().item(ToolNodeResult.ok(
                                new JsonObject().put("ok", false).put("error", "Station offline").encode()));
                    }
                    if (listenerName == null) {
                        return sendQueue(brandName, fragment, MixingType.SONG_ONLY, null, internalRestCall);
                    }
                    UUID traceId = UUID.randomUUID();
                    return aiAgentService.getById(stream.getAiAgentId())
                            .chain(agent -> {
                                LanguageTag language = AiHelperUtils.selectLanguageByWeight(agent);
                                return introTtsGenerator.generateCustomIntroAudioFile(
                                        namedIntro(listenerName, fragment, language), agent, language,
                                        "chat-play-code", traceId, brandName, 0)
                                        .chain(introResult -> {
                                            IntroInfoDTO introDto = new IntroInfoDTO(introResult.filePath(), introResult.durationSeconds());
                                            introDto.setGain(introResult.gain());
                                            introDto.setEngineType(introResult.engineType());
                                            return sendQueue(brandName, fragment, MixingType.INTRO_SONG,
                                                    Map.of(IntroKey.INTRO_1, introDto), internalRestCall, traceId, listenerName);
                                        });
                            })
                            .onFailure().recoverWithUni(err ->
                                    sendQueue(brandName, fragment, MixingType.SONG_ONLY, null, internalRestCall));
                })
                .onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }

    private static Uni<ToolNodeResult> sendQueue(
            String brandName, SoundFragment fragment, MixingType mixingType,
            Map<IntroKey, IntroInfoDTO> filePaths, InternalRestCall internalRestCall) {
        return sendQueue(brandName, fragment, mixingType, filePaths, internalRestCall, UUID.randomUUID(), null);
    }

    private static Uni<ToolNodeResult> sendQueue(
            String brandName, SoundFragment fragment, MixingType mixingType,
            Map<IntroKey, IntroInfoDTO> filePaths, InternalRestCall internalRestCall,
            UUID traceId, String listenerName) {
        SongQueueMessageDTO dto = new SongQueueMessageDTO();
        dto.setMessageId(UUID.randomUUID());
        dto.setTraceId(traceId);
        dto.setTimestamp(System.currentTimeMillis());
        dto.setBrandSlug(brandName);
        dto.setSceneTitle("chat-play-code");
        dto.setMergingMethod(mixingType);
        dto.setPriority(StreamPriority.GENTLE_INTERRUPT.getValue());
        dto.setSongs(Map.of(SongKey.SONG_1, new SongInfoDTO(fragment.getId(), 0)));
        if (filePaths != null) {
            dto.setFilePaths(filePaths);
        }
        JsonObject result = new JsonObject()
                .put("ok", true)
                .put("brandName", brandName)
                .put("songId", fragment.getId().toString())
                .put("title", fragment.getTitle())
                .put("artist", fragment.getArtist())
                .put("mixingType", mixingType.name());
        if (listenerName != null) {
            result.put("listenerName", listenerName);
        }
        return internalRestCall.addSongToQueue(dto).replaceWith(ToolNodeResult.ok(result.encode()));
    }

    static String namedIntro(String name, SoundFragment fragment, LanguageTag language) {
        String title = fragment.getTitle() != null ? fragment.getTitle() : "this track";
        String artist = fragment.getArtist() != null ? fragment.getArtist() : "";
        List<String> options = introsFor(language);
        String template = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        return template
                .replace("{name}", name)
                .replace("{title}", title)
                .replace("{artist}", artist.isBlank() ? title : artist);
    }

    private static List<String> introsFor(LanguageTag language) {
        LanguageCode code = language != null ? language.toLanguageCode() : LanguageCode.en;
        List<String> options = INTROS.get(code);
        if (options == null || options.isEmpty()) {
            options = INTROS.getOrDefault(LanguageCode.en, FALLBACK_INTROS);
        }
        return options;
    }

    private static Map<LanguageCode, List<String>> loadIntros() {
        Map<LanguageCode, List<String>> byLanguage = new EnumMap<>(LanguageCode.class);
        try {
            JsonObject root = new JsonObject(ResourceUtil.loadResourceAsString(INTROS_RESOURCE));
            for (String key : root.fieldNames()) {
                LanguageCode code;
                try {
                    code = LanguageCode.valueOf(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                JsonArray messages = root.getJsonArray(key);
                if (messages == null || messages.isEmpty()) {
                    continue;
                }
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < messages.size(); i++) {
                    texts.add(messages.getString(i));
                }
                byLanguage.put(code, List.copyOf(texts));
            }
        } catch (Exception ignored) {
            byLanguage.put(LanguageCode.en, FALLBACK_INTROS);
        }
        if (byLanguage.isEmpty()) {
            byLanguage.put(LanguageCode.en, FALLBACK_INTROS);
        }
        return Map.copyOf(byLanguage);
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

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
