package com.semantyca.jesoos.service.maintenance;

import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.file.FileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class LocalFileCleanupService {
    private static final Logger LOGGER = Logger.getLogger(LocalFileCleanupService.class);
    private static final Duration TEMP_FILE_MAX_AGE = Duration.ofHours(2);
    private static final Duration ENTITY_FILE_MAX_AGE = Duration.ofDays(1);
    private final FileSystem fileSystem;
    private final List<String> managedDirectories;

    @Inject
    public LocalFileCleanupService(JesoosConfig config, Vertx vertx) {
        this.fileSystem = vertx.fileSystem();
        String baseUploadPath = config.getPathUploads();
        this.managedDirectories = List.of(
                baseUploadPath + "/sound-fragments-controller"
        );
    }

    public Uni<Void> cleanupTempFilesForUser(String username) {
        return Uni.createFrom().item(() -> {
                    Path tempDir = Paths.get(managedDirectories.getFirst(), username, "temp");
                    if (Files.exists(tempDir)) {
                        cleanupTempDirectory(tempDir);
                        LOGGER.infof("Cleaned up temp files for user: %s", username);
                    }
                    return null;
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    public Uni<Void> cleanupEntityFiles(String username, String entityId) {
        return Uni.createFrom().item(() -> {
                    Path entityDir = Paths.get(managedDirectories.getFirst(), username, entityId);
                    if (Files.exists(entityDir)) {
                        CleanupResult result = cleanupEntityDirectory(entityDir);
                        LOGGER.infof("Cleaned up entity files for user: %s, entity: %s - %s files, %s bytes",
                                username, entityId, result.entityFilesDeleted, result.bytesFreed);
                    }
                    return null;
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }


    private CleanupResult cleanupEntityDirectory(Path entityDir) {
        CleanupResult result = new CleanupResult();

        try (Stream<Path> files = Files.list(entityDir)) {
            Instant cutoffTime = Instant.now().minus(ENTITY_FILE_MAX_AGE);

            for (Path file : files.toArray(Path[]::new)) {
                if (Files.isRegularFile(file)) {
                    try {
                        Instant fileTime = Files.getLastModifiedTime(file).toInstant();
                        if (fileTime.isBefore(cutoffTime)) {
                            long fileSize = Files.size(file);
                            Files.delete(file);
                            result.entityFilesDeleted++;
                            result.bytesFreed += fileSize;
                            LOGGER.debugf("Deleted old entity file: %s", file);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to delete entity file: %s", file, e);
                    }
                }
            }

            if (isDirectoryEmpty(entityDir)) {
                Files.delete(entityDir);
                LOGGER.debugf("Removed empty entity directory: %s", entityDir);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to cleanup entity directory: %s", entityDir, e);
        }

        return result;
    }

    private void cleanupTempDirectory(Path tempDir) {
        CleanupResult result = new CleanupResult();

        try {
            if (isSpecialTempDirectory(tempDir.getParent())) {
                // Direct cleanup for audio-processing and playlist-processing
                cleanupTempFiles(tempDir, result);
            } else {
                // Check if it's a temp subdirectory or direct temp files
                if (Files.exists(tempDir.resolve("temp"))) {
                    cleanupTempFiles(tempDir.resolve("temp"), result);
                } else {
                    cleanupTempFiles(tempDir, result);
                }
            }

            if (isDirectoryEmpty(tempDir)) {
                Files.delete(tempDir);
                LOGGER.debugf("Removed empty temp directory: %s", tempDir);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to cleanup temp directory: %s", tempDir, e);
        }

    }
    
    private boolean isSpecialTempDirectory(Path directory) {
        String dirName = directory.getFileName().toString();
        return "audio-processing".equals(dirName) || "playlist-processing".equals(dirName);
    }


    private void cleanupTempFiles(Path directory, CleanupResult result) {
        try (Stream<Path> files = Files.list(directory)) {
            Instant cutoffTime = Instant.now().minus(TEMP_FILE_MAX_AGE);

            for (Path file : files.toArray(Path[]::new)) {
                if (Files.isRegularFile(file)) {
                    try {
                        Instant fileTime = Files.getLastModifiedTime(file).toInstant();
                        if (fileTime.isBefore(cutoffTime)) {
                            long fileSize = Files.size(file);
                            Files.delete(file);
                            result.tempFilesDeleted++;
                            result.bytesFreed += fileSize;
                            LOGGER.debugf("Deleted old temp file: %s", file);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to delete temp file: %s", file, e);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to cleanup temp files in directory: %s", directory, e);
        }
    }

    private boolean isDirectoryEmpty(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.findFirst().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
  
    public Uni<Void> cleanupAfterSuccessfulUpload(String username, String entityId, String fileName) {
        return fileSystem.delete(Paths.get(managedDirectories.getFirst(), username, entityId, fileName).toString())
                .onItem().invoke(() -> LOGGER.debugf("Cleaned up local file after successful upload: %s/%s/%s",
                        username, entityId, fileName))
                .onFailure().invoke(e -> LOGGER.warnf("Failed to cleanup local file: %s/%s/%s",
                        username, entityId, fileName, e))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private static class CleanupResult {
        long tempFilesDeleted = 0;
        long entityFilesDeleted = 0;
        long bytesFreed = 0;
    }
}