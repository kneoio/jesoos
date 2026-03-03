package com.semantyca.djinn.repository.soundfragment;

import com.semantyca.core.model.FileMetadata;
import com.semantyca.core.repository.file.IFileStorage;
import io.kneo.core.repository.exception.attachment.FileRetrievalFailureException;
import io.kneo.core.repository.exception.attachment.MissingFileRecordException;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class SoundFragmentFileHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentFileHandler.class);

    private final PgPool client;
    private final IFileStorage fileStorage;

    @Inject
    public SoundFragmentFileHandler(PgPool client, @Named("hetzner") IFileStorage fileStorage) {
    //public SoundFragmentFileHandler(PgPool client, @Named("digitalOcean") IFileStorage fileStorage) {
        this.client = client;
        this.fileStorage = fileStorage;
    }

    public Uni<FileMetadata> getFirstFile(UUID id) {
        String sql = "SELECT f.file_key FROM _files f WHERE f.parent_id = $1";
        return retrieveFileFromStorage(id, sql, Tuple.of(id));
    }

    private Uni<FileMetadata> retrieveFileFromStorage(UUID id, String sql, Tuple parameters) {
        return client.preparedQuery(sql)
                .execute(parameters)
                .onFailure().invoke(failure -> LOGGER.error("Database query failed for ID: {}", id, failure))
                .onItem().transformToUni(rows -> {
                    if (rows.rowCount() == 0) {
                        LOGGER.warn("No file record found for ID: {}", id);
                        return Uni.createFrom().failure(new MissingFileRecordException("File not found: " + id));
                    }

                    String fileKey = rows.iterator().next().getString("file_key");
                    LOGGER.debug("Retrieving file with key: {} for ID: {}", fileKey, id);

                    return fileStorage.retrieveFile(fileKey)
                            .onItem().invoke(file -> LOGGER.debug("File retrieval successful for ID: {}", id))
                            .onFailure().recoverWithUni(ex -> {
                                LOGGER.error("File retrieval failed - ID: {}, Key: {}, Error: {}", id, fileKey, ex.getMessage());
                                String errorMsg = String.format("File retrieval failed - ID: %s, Key: %s, Error: %s",
                                        id, fileKey, ex.getClass().getSimpleName());
                                FileRetrievalFailureException fnf = new FileRetrievalFailureException(errorMsg);
                                fnf.initCause(ex);
                                return Uni.createFrom().<FileMetadata>failure(fnf);
                            });
                });
    }
}