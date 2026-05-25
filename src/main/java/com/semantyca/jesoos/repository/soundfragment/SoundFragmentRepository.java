package com.semantyca.jesoos.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.core.model.cnst.FileStorageType;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.IFileStorage;
import com.semantyca.core.repository.exception.DocumentHasNotFoundException;
import com.semantyca.core.repository.exception.DocumentModificationAccessException;
import com.semantyca.core.repository.exception.UploadAbsenceException;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.core.util.WebHelper;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.core.model.cnst.RlsActionType;
import com.semantyca.jesoos.dto.RlsActionDTO;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.semantyca.mixpla.repository.MixplaNameResolver.SOUND_FRAGMENT;


@ApplicationScoped
public class SoundFragmentRepository extends SoundFragmentRepositoryAbstract {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SOUND_FRAGMENT);
    private final SoundFragmentQueryBuilder queryBuilder;
    private final IFileStorage fileStorage;
    private final SoundFragmentBrandAssociationHandler brandHandler;

    public SoundFragmentRepository() {
        super();
        this.brandHandler = null;
        this.fileStorage = null;
        this.queryBuilder = null;
    }

    @Inject
    public SoundFragmentRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository,
                                   SoundFragmentQueryBuilder queryBuilder, @Named("hetzner") IFileStorage fileStorage, SoundFragmentBrandAssociationHandler brandHandler) {
        super(client, mapper, rlsRepository);
        this.queryBuilder = queryBuilder;
        this.fileStorage = fileStorage;
        this.brandHandler = brandHandler;
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset,
                                           final IUser user, final SoundFragmentFilter filter) {
        assert queryBuilder != null;
        String sql = queryBuilder.buildGetAllQuery(entityData.getTableName(), entityData.getRlsName(),
                user, false, filter, limit, offset);

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm()))
                    .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                    .onItem().transformToUni(row -> from(row, false, false, false))
                    .concatenate()
                    .collect().asList();
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, SoundFragmentFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId() + " AND t.archived = 0";

        if (filter != null && filter.isActivated()) {
            assert queryBuilder != null;
            sql += queryBuilder.buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm()))
                    .onItem().transform(rows -> rows.iterator().next().getInteger(0));
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<SoundFragment> findById(UUID uuid, long userID, boolean includeGenres, boolean includeFiles) {
        String sql = "SELECT theTable.*, rls.*" +
                String.format(" FROM %s theTable JOIN %s rls ON theTable.id = rls.entity_id ", entityData.getTableName(), entityData.getRlsName()) +
                "WHERE rls.reader = $1 AND theTable.id = $2 AND theTable.archived = 0";

        return client.preparedQuery(sql)
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, includeGenres, includeFiles, true);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }

    public Uni<SoundFragment> findById(UUID uuid) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, false, false, false);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }

    public Uni<SoundFragment> findByArtistAndDate(String artist, OffsetDateTime startOfDay, OffsetDateTime endOfDay) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " " +
                "WHERE artist = $1 AND reg_date >= $2 AND reg_date < $3 AND archived = 0 " +
                "ORDER BY reg_date DESC LIMIT 1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(artist, startOfDay, endOfDay))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, false, false, false);
                    } else {
                        return Uni.createFrom().nullItem();
                    }
                });
    }

    public Uni<List<BrandSoundFragment>> findForBrandWithFilter(UUID brandId, String keyword, SoundFragmentFilter filter, int limit, int offset, IUser user) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findForBrandWithFilter(brandId, keyword, filter, limit, offset, user);
    }


    public Uni<List<SoundFragment>> findByTypeAndBrand(PlaylistItemType type, UUID brandId, int limit, int offset) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.getBrandSongs(brandId, type, limit, offset);
    }

    public Uni<List<SoundFragment>> findByFilter(UUID brandId, SoundFragmentFilter filter, int limit) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findByFilter(brandId, filter, limit);
    }

    public Uni<List<SoundFragment>> findByFilterOldest(UUID brandId, SoundFragmentFilter filter, int limit) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findByFilterOldest(brandId, filter, limit);
    }

    public Uni<List<SoundFragment>> findByFilterOldest(UUID brandId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findByFilterOldest(brandId, filter, limit, excludeIds);
    }

    public Uni<List<SoundFragment>> findByFilterRandom(UUID brandId, SoundFragmentFilter filter, int limit) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findByFilterRandom(brandId, filter, limit);
    }

    public Uni<List<SoundFragment>> findByFilterRandom(UUID brandId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findByFilterRandom(brandId, filter, limit, excludeIds);
    }

    public Uni<List<SoundFragment>> findByFilter(UUID brandId, SoundFragmentFilter filter, int limit, Set<UUID> excludeIds) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findByFilter(brandId, filter, limit, excludeIds);
    }

    public Uni<io.vertx.core.json.JsonObject> getBrandCatalogSummary(UUID brandId) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.getBrandCatalogSummary(brandId);
    }

    public Uni<List<SoundFragment>> findByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        String placeholders = ids.stream()
                .map(id -> "'" + id.toString() + "'")
                .collect(java.util.stream.Collectors.joining(","));
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t " +
                "WHERE t.id IN (" + placeholders + ") AND t.archived = 0";
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }

    /**
     * Loads fragments by id for scheduling; orders by higher {@code boost}, then fewer
     * {@code played_by_brand_count} for this brand (fragments without a brand row sort like 0 plays).
     */
    public Uni<List<SoundFragment>> findByIdsForBrand(UUID brandId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        String placeholders = ids.stream()
                .map(id -> "'" + id.toString() + "'")
                .collect(Collectors.joining(","));
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t "
                + "LEFT JOIN mixpla__brand_sound_fragments bsf ON bsf.sound_fragment_id = t.id "
                + "AND bsf.brand_id = '" + brandId + "' "
                + "WHERE t.id IN (" + placeholders + ") AND t.archived = 0 "
                + "ORDER BY COALESCE(t.boost, 0) DESC, "
                + "COALESCE(bsf.played_by_brand_count, 0) ASC, "
                + "t.id ASC";
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<SoundFragment> insert(SoundFragment doc, List<UUID> representedInBrands, List<RlsActionDTO> rlsActions, IUser user) {
        OffsetDateTime nowTime = OffsetDateTime.now(ZoneOffset.UTC);
        final List<FileMetadata> originalFiles = doc.getFileMetadataList();

        final List<FileMetadata> filesToProcess = (originalFiles != null && !originalFiles.isEmpty())
                ? List.of(originalFiles.getFirst())
                : null;

        if (filesToProcess != null) {
            FileMetadata meta = filesToProcess.getFirst();
            Path filePath = meta.getFilePath();
            if (filePath == null) {
                throw new IllegalArgumentException("File metadata contains an entry with a null file path.");
            }
            if (!Files.exists(filePath)) {
                throw new UploadAbsenceException("Upload file not found at path: " + filePath);
            }
            meta.setFileOriginalName(filePath.getFileName().toString());
            meta.setSlugName(WebHelper.generateSlug(doc.getArtist(), doc.getTitle()));
            String doKey = WebHelper.generateSlugPath("music", doc.getArtist(), String.valueOf(UUID.randomUUID()));
            meta.setFileKey(doKey);
            meta.setMimeType(detectMimeType(filePath.toString()));
            doc.setFileMetadataList(filesToProcess);
        }

        return executeInsertTransaction(doc, user, nowTime, Uni.createFrom().voidItem(), representedInBrands, rlsActions)
                .onItem().transformToUni(insertedDoc -> {
                    if (filesToProcess != null) {
                        FileMetadata meta = filesToProcess.getFirst();
                        assert fileStorage != null;
                        return fileStorage.uploadFile(
                                        meta.getFileKey(),
                                        meta.getFilePath().toString(),
                                        meta.getMimeType()
                                )
                                .onItem().invoke(storedKey -> LOGGER.debug("File stored with key: {} for doc ID: {}", storedKey, insertedDoc.getId()))
                                .onItem().transform(ignored -> insertedDoc)
                                .onFailure().recoverWithUni(ex -> {
                                    LOGGER.error("File failed to store for doc ID: {}. DB record was created.", insertedDoc.getId(), ex);
                                    return Uni.createFrom().failure(new RuntimeException("File storage failed after sound fragment creation", ex));
                                });
                    }
                    return Uni.createFrom().item(insertedDoc);
                });
    }

    private Uni<SoundFragment> executeInsertTransaction(SoundFragment doc, IUser user, OffsetDateTime regDate,
                                                        Uni<Void> fileUploadCompletionUni, List<UUID> representedInBrands,
                                                        List<RlsActionDTO> rlsActions) {
        return fileUploadCompletionUni.onItem().transformToUni(v -> {
            String sql = String.format(
                    "INSERT INTO %s (reg_date, author, last_mod_date, last_mod_user, source, status, type, " +
                            "title, artist, album, length, description, slug_name, expires_at) " +
                            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) RETURNING id;",
                    entityData.getTableName()
            );

            Long lengthMillis = doc.getLength() != null ? doc.getLength().toMillis() : null;

            Tuple params = Tuple.tuple()
                    .addOffsetDateTime(regDate)
                    .addLong(user.getId())
                    .addOffsetDateTime(regDate)
                    .addLong(user.getId())
                    .addString(doc.getSource().name())
                    .addInteger(doc.getStatus())
                    .addString(doc.getType().name())
                    .addString(doc.getTitle())
                    .addString(doc.getArtist())
                    .addString(doc.getAlbum())
                    .addLong(lengthMillis)
                    .addString(doc.getDescription())
                    .addString(doc.getSlugName())
                    .addOffsetDateTime(doc.getExpiresAt());

            return client.withTransaction(tx -> tx.preparedQuery(sql)
                    .execute(params)
                    .onItem().transform(result -> result.iterator().next().getUUID("id"))
                    .onItem().transformToUni(id -> {
                        Uni<Void> fileMetadataUni = insertFileMetadata(tx, id, doc);
                        return fileMetadataUni
                                .onItem().transformToUni(ignored -> insertGenreAssociations(tx, id, doc.getGenres()))
                                .onItem().transformToUni(ignored -> upsertLabels(tx, id, doc.getLabels()))
                                .onItem().transformToUni(ignored -> insertRLSPermissions(tx, id, entityData, user))
                                .onItem().transformToUni(ignored -> applyRlsActions(tx, id, rlsActions))
                                .onItem().transformToUni(ignored -> {
                                    assert brandHandler != null;
                                    return brandHandler.insertBrandAssociations(tx, id, representedInBrands, user);
                                })
                                .onItem().transform(ignored -> id);
                    })
            );
        }).onItem().transformToUni(id -> findById(id, user.getId(), true, true));
    }

    private Uni<Void> applyRlsActions(SqlClient tx, UUID entityId, List<RlsActionDTO> actions) {
        if (actions == null || actions.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        String grantSql = String.format(
                "INSERT INTO %s (reader, entity_id, can_edit, can_delete) VALUES ($1, $2, $3, $4) " +
                "ON CONFLICT (reader, entity_id) DO UPDATE SET " +
                "can_edit = EXCLUDED.can_edit, can_delete = EXCLUDED.can_delete, reading_time = now()",
                entityData.getRlsName()
        );
        String revokeSql = String.format(
                "DELETE FROM %s WHERE reader = $1 AND entity_id = $2",
                entityData.getRlsName()
        );
        List<Uni<Void>> unis = new java.util.ArrayList<>();
        for (RlsActionDTO action : actions) {
            if (action.getAction() == RlsActionType.GRANT) {
                unis.add(tx.preparedQuery(grantSql)
                        .execute(Tuple.of(action.getUserId(), entityId, action.isCanEdit(), action.isCanDelete()))
                        .onItem().ignore().andContinueWithNull());
            } else if (action.getAction() == RlsActionType.REVOKE) {
                unis.add(tx.preparedQuery(revokeSql)
                        .execute(Tuple.of(action.getUserId(), entityId))
                        .onItem().ignore().andContinueWithNull());
            }
        }
        if (unis.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.combine().all().unis(unis).discardItems();
    }

    private Uni<Void> insertGenreAssociations(SqlClient tx, UUID soundFragmentId, List<UUID> genreIds) {
        if (genreIds == null || genreIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String insertSql = "INSERT INTO mixpla__sound_fragment_genres (sound_fragment_id, genre_id) VALUES ($1, $2)";
        List<Tuple> params = genreIds.stream()
                .map(id -> Tuple.of(soundFragmentId, id))
                .collect(Collectors.toList());

        return tx.preparedQuery(insertSql)
                .executeBatch(params)
                .onItem().ignore().andContinueWithNull();
    }

    private Uni<Void> insertFileMetadata(SqlClient tx, UUID id, SoundFragment doc) {
        if (doc.getFileMetadataList() == null || doc.getFileMetadataList().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String filesSql = "INSERT INTO _files (parent_table, parent_id, storage_type, " +
                "mime_type, file_original_name, file_key, file_bin, slug_name) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";
        List<Tuple> filesParams = doc.getFileMetadataList().stream()
                .map(meta -> Tuple.of(
                                        entityData.getTableName(),
                                        id,
                                        FileStorageType.HETZNER,
                                        meta.getMimeType(),
                                        meta.getFileOriginalName(),
                                        meta.getFileKey()
                                )
                                .addValue(meta.getFileBin())
                                .addValue(meta.getSlugName())
                ).collect(Collectors.toList());

        return tx.preparedQuery(filesSql).executeBatch(filesParams).onItem().ignore().andContinueWithNull();
    }

    private Uni<Void> upsertLabels(SqlClient tx, UUID fragmentId, List<UUID> labels) {
        if (labels == null || labels.isEmpty()) {
            return tx.preparedQuery("DELETE FROM mixpla__sound_fragment_labels WHERE id = $1")
                    .execute(Tuple.of(fragmentId))
                    .replaceWithVoid();
        }

        String deleteSql = "DELETE FROM mixpla__sound_fragment_labels WHERE id = $1";
        String insertSql = "INSERT INTO mixpla__sound_fragment_labels (id, label_id) VALUES ($1, $2) ON CONFLICT DO NOTHING";

        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(fragmentId))
                .chain(() -> Multi.createFrom().iterable(labels)
                        .onItem().transformToUni(labelId ->
                                tx.preparedQuery(insertSql).execute(Tuple.of(fragmentId, labelId))
                        )
                        .merge()
                        .collect().asList()
                        .replaceWithVoid());
    }

    public Uni<SoundFragment> update(UUID id, SoundFragment doc, List<UUID> representedInBrands, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }

                    return findById(id, user.getId(), true,  true)
                            .onItem().transformToUni(existingDoc -> {
                                final List<FileMetadata> originalFiles = doc.getFileMetadataList();
                                final List<FileMetadata> newFiles = (originalFiles != null && !originalFiles.isEmpty())
                                        ? List.of(originalFiles.getFirst())
                                        : null;

                                Uni<Void> fileStoredUni = handleFileUpdate(id, doc, newFiles);

                                return fileStoredUni.onItem().transformToUni(ignored -> {
                                    OffsetDateTime nowTime = OffsetDateTime.now(ZoneOffset.UTC);

                                    return client.withTransaction(tx -> {
                                        Uni<Void> chain = Uni.createFrom().voidItem();
                                        if (newFiles != null) {
                                            chain = deleteExistingFiles(tx, id)
                                                    .onItem().transformToUni(v -> insertNewFiles(tx, id, newFiles));
                                        }
                                        return chain
                                                .onItem().transformToUni(v -> updateGenreAssociations(tx, id, doc.getGenres()))
                                                .onItem().transformToUni(v -> upsertLabels(tx, id, doc.getLabels()))
                                                .onItem().transformToUni(v -> {
                                                    assert brandHandler != null;
                                                    return brandHandler.updateBrandAssociations(tx, id, representedInBrands, user);
                                                })
                                                .onItem().transformToUni(v -> updateSoundFragmentRecord(tx, id, doc, user, nowTime));
                                    }).onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return findById(id, user.getId(), true,  true);
                                    });
                                });
                            });
                });
    }

    private Uni<Void> handleFileUpdate(UUID id, SoundFragment doc, List<FileMetadata> newFiles) {
        if (newFiles == null) {
            return Uni.createFrom().voidItem();
        }

        FileMetadata meta = newFiles.getFirst();
        if (meta.getFilePath() == null) {
            return Uni.createFrom().voidItem();
        }

        String localPath = meta.getFilePath().toString();
        Path path = Paths.get(localPath);
        if (!Files.exists(path)) {
            return Uni.createFrom().failure(new UploadAbsenceException("Upload file not found at path: " + localPath));
        }

        String doKey = WebHelper.generateSlugPath("music", doc.getArtist(), String.valueOf(UUID.randomUUID()));
        meta.setFileKey(doKey);
        meta.setMimeType(detectMimeType(localPath));
        meta.setFileOriginalName(path.getFileName().toString());
        meta.setSlugName(WebHelper.generateSlug(doc.getArtist(), doc.getTitle()));

        LOGGER.debug("Storing file - Key: {}, Path: {}, Artist: {}, Title: {}", doKey, localPath, doc.getArtist(), doc.getTitle());

        assert fileStorage != null;
        return fileStorage.uploadFile(doKey, localPath, meta.getMimeType())
                .onItem().invoke(storedKey -> LOGGER.debug("File stored with key: {} for doc ID: {}", storedKey, id))
                .onFailure().invoke(ex -> LOGGER.error("Failed to store file with key: {}", doKey, ex))
                .onItem().ignore().andContinueWithNull();
    }


    private Uni<Void> deleteExistingFiles(SqlClient tx, UUID id) {
        String deleteSql = String.format("DELETE FROM _files WHERE parent_id = $1 AND parent_table = '%s'", entityData.getTableName());
        return tx.preparedQuery(deleteSql).execute(Tuple.of(id)).onItem().ignore().andContinueWithNull();
    }

    private Uni<Void> insertNewFiles(SqlClient tx, UUID id, List<FileMetadata> newFiles) {
        if (newFiles == null) {
            return Uni.createFrom().voidItem();
        }

        String filesSql = "INSERT INTO _files (parent_table, parent_id, storage_type, " +
                "mime_type, file_original_name, file_key, file_bin, slug_name) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";
        FileMetadata meta = newFiles.getFirst();
        Tuple fileParams = Tuple.of(
                        entityData.getTableName(),
                        id,
                        FileStorageType.HETZNER,
                        meta.getMimeType(),
                        meta.getFileOriginalName(),
                        meta.getFileKey()
                )
                .addValue(meta.getFileBin())
                .addValue(meta.getSlugName());

        return tx.preparedQuery(filesSql).execute(fileParams).onItem().ignore().andContinueWithNull();
    }

    private Uni<Void> updateGenreAssociations(SqlClient tx, UUID soundFragmentId, List<UUID> genreIds) {
        String deleteSql = "DELETE FROM mixpla__sound_fragment_genres WHERE sound_fragment_id = $1";
        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToUni(ignored -> insertGenreAssociations(tx, soundFragmentId, genreIds));
    }

    private Uni<RowSet<Row>> updateSoundFragmentRecord(SqlClient tx, UUID id, SoundFragment doc, IUser user, OffsetDateTime nowTime) {
        String updateSql = String.format("UPDATE %s SET last_mod_user=$1, last_mod_date=$2, " +
                        "status=$3, type=$4, title=$5, " +
                        "artist=$6, album=$7, length=$8, description=$9, slug_name=$10, expires_at=$11 WHERE id=$12;",
                entityData.getTableName());

        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addOffsetDateTime(nowTime)
                .addInteger(doc.getStatus())
                .addString(doc.getType().name())
                .addString(doc.getTitle())
                .addString(doc.getArtist())
                .addString(doc.getAlbum())
                .addLong(doc.getLength() != null ? doc.getLength().toMillis() : null)
                .addString(doc.getDescription())
                .addString(doc.getSlugName())
                .addOffsetDateTime(doc.getExpiresAt())
                .addUUID(id);

        return tx.preparedQuery(updateSql).execute(params);
    }

}