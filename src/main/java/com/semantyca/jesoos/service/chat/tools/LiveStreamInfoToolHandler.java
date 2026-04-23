package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.ScenePool;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class LiveStreamInfoToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = Logger.getLogger(LiveStreamInfoToolHandler.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            BrandPool brandPool,
            ScenePool scenePool,
            String brandName,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        LiveStreamInfoToolHandler handler = new LiveStreamInfoToolHandler();
        String action = inputMap.getOrDefault("action", JsonValue.from("get_agenda")).toString().replace("\"", "");

        LOGGER.infof("[LiveStreamInfo] action=%s, brand=%s", action, brandName);

        return brandPool.get(brandName)
                .flatMap(stream -> {
                    JsonObject payload;

                    if (stream == null || stream.getAgenda() == null) {
                        payload = new JsonObject()
                                .put("ok", false)
                                .put("error", "Stream not active or agenda not available");
                    } else {
                        payload = buildAgendaPayload(stream.getAgenda().getLiveScenes(), scenePool.getActiveScene(brandName));
                    }

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[LiveStreamInfo] Failed for brand={}", brandName, err);
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "I couldn't get the stream info right now, please try again.");
                    return Uni.createFrom().voidItem();
                });
    }

    private static JsonObject buildAgendaPayload(List<LiveScene> scenes, LiveScene activeScene) {
        JsonArray schedule = new JsonArray();

        LocalTime now = LocalTime.now();
        List<LocalTime> startTimes = scenes.stream()
                .map(LiveScene::getOriginalStartTime)
                .toList();

        for (int i = 0; i < scenes.size(); i++) {
            LiveScene scene = scenes.get(i);
            LocalTime nextStart = (i + 1 < startTimes.size()) ? startTimes.get(i + 1) : null;
            boolean isCurrent = activeScene != null
                    && scene.getSceneId() != null
                    && scene.getSceneId().equals(activeScene.getSceneId());

            JsonObject entry = new JsonObject()
                    .put("title", scene.getSceneTitle())
                    .put("starts_at", scene.getOriginalStartTime() != null ? scene.getOriginalStartTime().format(TIME_FMT) : "?")
                    .put("ends_at", nextStart != null ? nextStart.format(TIME_FMT) : "?")
                    .put("current", isCurrent);

            schedule.add(entry);
        }

        JsonObject payload = new JsonObject()
                .put("ok", true)
                .put("schedule", schedule);

        if (activeScene != null) {
            payload.put("now_playing_scene", activeScene.getSceneTitle());
            payload.put("upcoming_songs", buildUpcomingSongs(activeScene));
        }

        return payload;
    }

    private static JsonArray buildUpcomingSongs(LiveScene scene) {
        JsonArray songs = new JsonArray();
        if (scene.getTimeline() == null) return songs;

        int count = 0;
        for (TimelineEntry entry : scene.getTimeline()) {
            if (entry.getSongs() == null) continue;
            entry.getSongs().forEach(songEntry -> {
                if (songEntry.getSoundFragment() != null) {
                    songs.add(new JsonObject()
                            .put("title", songEntry.getSoundFragment().getTitle())
                            .put("artist", songEntry.getSoundFragment().getArtist()));
                }
            });
            if (++count >= 5) break;
        }
        return songs;
    }
}
