package com.semantyca.jesoos.rest;

import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.util.FileSecurityUtils;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.PublicChatService;
import com.semantyca.jesoos.service.chat.tools.ListenerLabelCache;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@ApplicationScoped
public class ChatUploadResource extends AbstractResource {
    private static final Logger LOGGER = Logger.getLogger(ChatUploadResource.class);

    @Inject
    PublicChatService publicChatService;

    @Inject
    ListenerService listenerService;

    @Inject
    ListenerLabelCache listenerLabelCache;

    @Inject
    JesoosConfig config;

    public void setupRoutes(Router router) {
        router.get("/jesoos/chat/listener-profile").handler(ctx -> {
            String token = ctx.request().getParam("token");

            publicChatService.authenticateUserFromToken(token)
                    .subscribe().with(
                            user -> {
                                if (user instanceof AnonymousUser || user.getId() == 0) {
                                    ctx.response().setStatusCode(401)
                                            .putHeader("Content-Type", "application/json")
                                            .end(new JsonObject().put("error", "Authentication required").encode());
                                    return;
                                }
                                listenerService.getByUserId(user.getId())
                                        .subscribe().with(
                                                listener -> {
                                                    JsonObject userData = new JsonObject();
                                                    if (listener != null && listener.getUserData() != null
                                                            && listener.getUserData().getData() != null) {
                                                        listener.getUserData().getData().forEach(
                                                                (k, v) -> userData.put(k, v != null ? v.toString() : null)
                                                        );
                                                    }
                                                    java.util.UUID artistLabelId = listenerLabelCache.get("artist");
                                                    boolean isArtist = artistLabelId != null
                                                            && listener != null
                                                            && listener.getLabels() != null
                                                            && listener.getLabels().contains(artistLabelId);
                                                    if (isArtist) {
                                                        userData.put("is_artist", "true");
                                                    }
                                                    ctx.response().setStatusCode(200)
                                                            .putHeader("Content-Type", "application/json")
                                                            .end(new JsonObject().put("user_data", userData).encode());
                                                },
                                                err -> ctx.response().setStatusCode(500)
                                                        .putHeader("Content-Type", "application/json")
                                                        .end(new JsonObject().put("error", "Failed to fetch profile").encode())
                                        );
                            },
                            err -> ctx.response().setStatusCode(401)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("error", "Authentication required").encode())
                    );
        });

        String path = "/jesoos/chat/upload-temp";

        router.route(path).handler(
                BodyHandler.create(config.getQuarkusFileUploadsPath())
                        .setUploadsDirectory(config.getQuarkusFileUploadsPath())
        );

        router.post(path).handler(ctx -> {
            String token = ctx.request().getParam("token");

            publicChatService.authenticateUserFromToken(token)
                    .subscribe().with(
                            user -> {
                                var uploads = ctx.fileUploads();
                                if (uploads == null || uploads.isEmpty()) {
                                    ctx.response().setStatusCode(400)
                                            .putHeader("Content-Type", "application/json")
                                            .end(new JsonObject().put("error", "No file uploaded").encode());
                                    return;
                                }

                                var upload = uploads.getFirst();
                                String safeFilename;
                                try {
                                    safeFilename = FileSecurityUtils.sanitizeFilename(upload.fileName());
                                } catch (SecurityException e) {
                                    ctx.response().setStatusCode(400)
                                            .putHeader("Content-Type", "application/json")
                                            .end(new JsonObject().put("error", "Invalid filename").encode());
                                    return;
                                }

                                String uploadDir = config.getPathUploads() + "/sound-fragments-controller";
                                Path destDir = Paths.get(uploadDir, user.getLogin(), "temp");
                                try {
                                    Files.createDirectories(destDir);
                                    Path destFile = FileSecurityUtils.secureResolve(destDir, safeFilename);
                                    Files.move(Paths.get(upload.uploadedFileName()), destFile, StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException | SecurityException e) {
                                    LOGGER.errorf("Failed to save upload for user %s: %s", user.getLogin(), e.getMessage());
                                    ctx.response().setStatusCode(500)
                                            .putHeader("Content-Type", "application/json")
                                            .end(new JsonObject().put("error", "Failed to save file").encode());
                                    return;
                                }

                                LOGGER.infof("Temp upload saved: %s for user %s", safeFilename, user.getLogin());
                                ctx.response().setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(new JsonObject().put("filename", safeFilename).encode());
                            },
                            err -> ctx.response().setStatusCode(401)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("error", "Authentication required").encode())
                    );
        });
    }
}
