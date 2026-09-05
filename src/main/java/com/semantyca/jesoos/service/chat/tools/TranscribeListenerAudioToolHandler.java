package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.external.STTClient;
import com.semantyca.jesoos.external.SttResult;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class TranscribeListenerAudioToolHandler extends BaseToolHandler {

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            UserService userService, JesoosConfig config, STTClient sttClient, long userId) {
        String tempFilename = ((String) inputMap.getOrDefault("temp_filename", "")).trim();
        if (tempFilename.isEmpty()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("usable", false)
                            .put("error", "temp_filename is required").encode()));
        }
        return userService.get(userId).chain(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("usable", false)
                                .put("error", "User not found").encode()));
            }
            IUser user = userOpt.get();
            Path file = AssessTrackToolHandler.resolveTempFile(config, user.getLogin(), tempFilename);
            if (!Files.isRegularFile(file)) {
                return Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("usable", false)
                                .put("error", "Uploaded file not found: " + tempFilename).encode()));
            }
            return sttClient.transcribe(file).map(TranscribeListenerAudioToolHandler::toJson);
        }).onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                new JsonObject().put("ok", false).put("usable", false)
                        .put("error", err.getMessage()).encode()));
    }

    private static ToolNodeResult toJson(SttResult stt) {
        boolean usable = stt.hasText();
        JsonObject json = new JsonObject()
                .put("ok", stt.ok())
                .put("usable", usable)
                .put("transcript", stt.transcript())
                .put("confidence", stt.confidence())
                .put("language", stt.languageCode());
        if (!stt.ok()) {
            json.put("error", stt.error());
        }
        return ToolNodeResult.ok(json.encode());
    }
}
