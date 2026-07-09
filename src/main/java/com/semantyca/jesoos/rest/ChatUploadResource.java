package com.semantyca.jesoos.rest;

import com.semantyca.core.util.FileSecurityUtils;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.service.chat.ChatAuthService;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.semantyca.core.util.WebHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@ApplicationScoped
public class ChatUploadResource extends AbstractResource {
    private static final Logger LOGGER = Logger.getLogger(ChatUploadResource.class);
    private static final String UPLOAD_CONTROLLER = "chat-upload-controller";

    @Inject
    ChatAuthService chatAuthService;

    @Inject
    PublicChatSessionManager sessionManager;

    @Inject
    JesoosConfig config;

    public void setupRoutes(Router router) {
        String uploadPath = "/jesoos/chat/upload-temp";
        router.route(uploadPath).handler(
                BodyHandler.create(config.getQuarkusFileUploadsPath())
                        .setUploadsDirectory(config.getQuarkusFileUploadsPath())
                        .setBodyLimit(config.getChatUploadMaxBodySizeBytes())
        );
        router.post(uploadPath).handler(this::handleUploadTemp);
    }

    private void handleUploadTemp(RoutingContext rc) {
        String token = rc.request().getParam("token");

        chatAuthService.authenticateUserFromToken(token)
                .subscribe().with(
                        user -> {
                            if (user.getId() == 0 || !sessionManager.hasUploadPermission(user.getId())) {
                                rc.fail(403, new IllegalArgumentException("Upload not available — ask the DJ first"));
                                return;
                            }

                            var uploads = rc.fileUploads();
                            if (uploads == null || uploads.isEmpty()) {
                                rc.fail(400, new IllegalArgumentException("No file uploaded"));
                                return;
                            }

                            var upload = uploads.getFirst();
                            String safeFilename;
                            try {
                                safeFilename = FileSecurityUtils.sanitizeFilename(upload.fileName());
                            } catch (SecurityException e) {
                                rc.fail(400, new IllegalArgumentException("Invalid filename"));
                                return;
                            }

                            String slug = WebHelper.generateSlug(safeFilename);
                            String extension = "";
                            int dot = slug.lastIndexOf('.');
                            if (dot >= 0) {
                                extension = slug.substring(dot);
                                slug = slug.substring(0, dot);
                            }
                            String uniqueFilename = slug + "-" + UUID.randomUUID().toString().substring(0, 8) + extension;

                            Path destDir = Paths.get(config.getPathUploads(), UPLOAD_CONTROLLER, user.getLogin(), "temp");
                            try {
                                Files.createDirectories(destDir);
                                Path destFile = FileSecurityUtils.secureResolve(destDir, uniqueFilename);
                                Files.move(Paths.get(upload.uploadedFileName()), destFile, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException | SecurityException e) {
                                rc.fail(500, new RuntimeException("Failed to save file for user " + user.getLogin() + ": " + e.getMessage(), e));
                                return;
                            }

                            LOGGER.infof("Temp upload saved: %s (original: %s) for user %s", uniqueFilename, safeFilename, user.getLogin());
                            rc.response().setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("filename", uniqueFilename).encode());
                        },
                        err -> rc.fail(401, new IllegalArgumentException("Authentication required"))
                );
    }
}
