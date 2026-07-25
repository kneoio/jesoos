package com.semantyca.jesoos.service.chat.tools;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.FileSecurityUtils;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.outbound.SpectraApiClient;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class AssessTrackToolHandler extends BaseToolHandler {

    private static final String UPLOAD_CONTROLLER = "chat-upload-controller";

    public static Uni<ToolNodeResult> execute(
            Map<String, Object> inputMap,
            SpectraApiClient spectraClient, UserService userService, JesoosConfig config, long userId) {
        String tempFilename = ((String) inputMap.getOrDefault("temp_filename", "")).trim();
        if (tempFilename.isEmpty()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "temp_filename is required").encode()));
        }
        return userService.get(userId).chain(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "User not found").encode()));
            }
            IUser user = userOpt.get();
            Path file = resolveTempFile(config, user.getLogin(), tempFilename);
            if (!Files.isRegularFile(file)) {
                return Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "Uploaded file not found: " + tempFilename).encode()));
            }
            return spectraClient.assess(file)
                    .map(analysis -> ToolNodeResult.ok(
                            new JsonObject()
                                    .put("ok", true)
                                    .put("is_music", analysis.getBoolean("is_music"))
                                    .put("analysis", analysis).encode()));
        }).onFailure().recoverWithItem(err -> ToolNodeResult.ok(
                new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
    }

    /** Temp upload location shared by /chat/upload-temp and SunoImportService. */
    static Path resolveTempFile(JesoosConfig config, String login, String tempFilename) {
        Path tempDir = Paths.get(config.getPathUploads(), UPLOAD_CONTROLLER, login, "temp");
        return FileSecurityUtils.secureResolve(tempDir, tempFilename);
    }
}
