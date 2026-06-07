package com.semantyca.jesoos.rest;

import com.semantyca.core.util.FileSecurityUtils;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.UploadFileDTO;
import com.semantyca.jesoos.service.chat.ChatAuthService;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class SoundFragmentUploadResource extends AbstractResource {
    private static final Logger LOGGER = Logger.getLogger(SoundFragmentUploadResource.class);
    private static final String UPLOAD_CONTROLLER = "chat-upload-controller";
    private static final long MAX_FILE_SIZE_BYTES = 200 * 1024 * 1024L;

    private static final class ChunkState {
        final int totalChunks;
        final AtomicInteger received = new AtomicInteger(0);
        final String safeFileName;

        ChunkState(int totalChunks, String safeFileName) {
            this.totalChunks = totalChunks;
            this.safeFileName = safeFileName;
        }
    }

    private final ConcurrentHashMap<String, ChunkState> chunkStateMap = new ConcurrentHashMap<>();

    @Inject
    ChatAuthService chatAuthService;

    @Inject
    JesoosConfig config;

    public void setupRoutes(Router router) {
        router.post("/jesoos/soundfragments/upload/chunk").handler(this::uploadChunk);
    }

    private void uploadChunk(RoutingContext rc) {
        String token = rc.request().getParam("token");
        String batchId = rc.request().getParam("batchId");
        String fileId = rc.request().getParam("fileId");
        String fileName = rc.request().getParam("fileName");
        String chunkIndexStr = rc.request().getParam("chunkIndex");
        String totalChunksStr = rc.request().getParam("totalChunks");

        if (batchId == null || batchId.isBlank()) { fail400(rc, "batchId required"); return; }
        if (fileId == null || !fileId.matches("[a-zA-Z0-9_-]+")) { fail400(rc, "invalid fileId"); return; }
        if (fileName == null || fileName.isBlank()) { fail400(rc, "fileName required"); return; }
        if (chunkIndexStr == null || totalChunksStr == null) { fail400(rc, "chunkIndex and totalChunks required"); return; }

        int chunkIndex, totalChunks;
        try {
            chunkIndex = Integer.parseInt(chunkIndexStr);
            totalChunks = Integer.parseInt(totalChunksStr);
        } catch (NumberFormatException e) {
            fail400(rc, "chunkIndex and totalChunks must be integers");
            return;
        }
        if (chunkIndex < 0 || totalChunks < 1 || chunkIndex >= totalChunks) {
            fail400(rc, "invalid chunk parameters");
            return;
        }

        final int ci = chunkIndex;
        final int tc = totalChunks;

        chatAuthService.authenticateUserFromToken(token)
                .subscribe().with(
                        user -> {
                            String safeFileName;
                            try {
                                safeFileName = FileSecurityUtils.sanitizeFilename(fileName);
                            } catch (SecurityException e) {
                                fail400(rc, "invalid filename");
                                return;
                            }

                            ChunkState state = chunkStateMap.computeIfAbsent(fileId,
                                    k -> new ChunkState(tc, safeFileName));

                            Path chunkDir;
                            Path chunkFile;
                            try {
                                chunkDir = Files.createDirectories(
                                        Paths.get(config.getPathUploads(), UPLOAD_CONTROLLER,
                                                user.getUserName(), ".chunks_" + fileId));
                                chunkFile = chunkDir.resolve("chunk_" + ci);
                            } catch (IOException e) {
                                LOGGER.errorf("Failed to create chunk directory: fileId=%s, error=%s", fileId, e.getMessage());
                                fail500(rc, "failed to prepare chunk storage");
                                return;
                            }

                            rc.request().setExpectMultipart(true);
                            rc.request().uploadHandler(upload -> {
                                upload.streamToFileSystem(chunkFile.toString());

                                upload.endHandler(v -> {
                                    try {
                                        if (!Files.exists(chunkFile)) {
                                            LOGGER.errorf("Chunk file missing after stream: fileId=%s, chunk=%d/%d", fileId, ci, tc);
                                            fail500(rc, "chunk data not received");
                                            return;
                                        }

                                        int received = state.received.incrementAndGet();
                                        int percentage = received * 100 / tc;

                                        if (received < tc) {
                                            rc.response().setStatusCode(200)
                                                    .putHeader("Content-Type", "application/json")
                                                    .end(Json.encode(UploadFileDTO.builder()
                                                            .id(fileId).status("uploading")
                                                            .percentage(percentage).batchId(batchId)
                                                            .name(safeFileName).build()));
                                        } else {
                                            assembleAndRespond(rc, fileId, batchId, safeFileName, chunkDir, tc, user.getUserName());
                                        }
                                    } catch (Exception e) {
                                        LOGGER.errorf("Error after chunk stream: fileId=%s, chunk=%d, error=%s", fileId, ci, e.getMessage());
                                        try { Files.deleteIfExists(chunkFile); } catch (IOException ignored) {}
                                        fail500(rc, "chunk processing failed");
                                    }
                                });

                                upload.exceptionHandler(err -> {
                                    LOGGER.warnf("Chunk stream error: fileId=%s, chunk=%d/%d, error=%s", fileId, ci, tc, err.getMessage());
                                    try { Files.deleteIfExists(chunkFile); } catch (IOException ignored) {}
                                    fail500(rc, "chunk upload failed");
                                });
                            });
                        },
                        err -> rc.response().setStatusCode(401)
                                .putHeader("Content-Type", "application/json")
                                .end("{\"error\":\"Authentication required\"}")
                );
    }

    private void assembleAndRespond(RoutingContext rc, String fileId, String batchId,
                                    String safeFileName, Path chunkDir, int totalChunks, String userName) {
        try {
            Path tempDir = Paths.get(config.getPathUploads(), UPLOAD_CONTROLLER, userName, "temp");
            Files.createDirectories(tempDir);

            Path destination = FileSecurityUtils.secureResolve(tempDir, safeFileName);
            if (!FileSecurityUtils.isPathWithinBase(tempDir, destination)) {
                LOGGER.errorf("Security violation: path traversal in assembled filename: fileId=%s, user=%s", fileId, userName);
                fail500(rc, "invalid file path");
                return;
            }

            Path tempAssembled = tempDir.resolve(".tmp_assembled_" + fileId + "_" + safeFileName);
            try (OutputStream out = Files.newOutputStream(tempAssembled)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path chunk = chunkDir.resolve("chunk_" + i);
                    Files.copy(chunk, out);
                    Files.delete(chunk);
                }
            }
            Files.move(tempAssembled, destination, StandardCopyOption.REPLACE_EXISTING);

            long size = Files.size(destination);
            if (size > MAX_FILE_SIZE_BYTES) {
                Files.deleteIfExists(destination);
                chunkStateMap.remove(fileId);
                fail400(rc, "file exceeds 200 MB limit");
                return;
            }

            try { Files.deleteIfExists(chunkDir); } catch (IOException ignored) {}
            chunkStateMap.remove(fileId);

            LOGGER.infof("Chunk assembly complete: fileId=%s, file=%s, size=%d, user=%s",
                    fileId, safeFileName, size, userName);

            rc.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(Json.encode(UploadFileDTO.builder()
                            .id(fileId).status("finished")
                            .percentage(100).batchId(batchId)
                            .name(safeFileName).build()));
        } catch (Exception e) {
            LOGGER.errorf("Chunk assembly failed: fileId=%s, error=%s", fileId, e.getMessage());
            chunkStateMap.remove(fileId);
            fail500(rc, "chunk assembly failed");
        }
    }

    private void fail400(RoutingContext rc, String msg) {
        rc.response().setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end("{\"error\":\"" + msg + "\"}");
    }

    private void fail500(RoutingContext rc, String msg) {
        rc.response().setStatusCode(500)
                .putHeader("Content-Type", "application/json")
                .end("{\"error\":\"" + msg + "\"}");
    }
}
