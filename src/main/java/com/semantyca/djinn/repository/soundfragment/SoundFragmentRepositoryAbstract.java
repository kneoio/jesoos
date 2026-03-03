package com.semantyca.djinn.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.djinn.model.soundfragment.SoundFragment;
import com.semantyca.djinn.repository.MixplaNameResolver;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.core.model.cnst.FileStorageType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlResult;
import io.vertx.mutiny.sqlclient.Tuple;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.semantyca.djinn.repository.MixplaNameResolver.SOUND_FRAGMENT;


public abstract class SoundFragmentRepositoryAbstract extends AsyncRepository {
    protected static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SOUND_FRAGMENT);

    public SoundFragmentRepositoryAbstract() {
        super();
    }

    public SoundFragmentRepositoryAbstract(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    protected Uni<SoundFragment> from(Row row, boolean includeGenres, boolean includeFiles, boolean includeLabels) {
        SoundFragment doc = new SoundFragment();
        setDefaultFields(doc, row);
        doc.setSource(SourceType.valueOf(row.getString("source")));
        doc.setStatus(row.getInteger("status"));
        doc.setType(PlaylistItemType.valueOf(row.getString("type")));
        doc.setTitle(row.getString("title"));
        doc.setArtist(row.getString("artist"));
        doc.setAlbum(row.getString("album"));
        
        if (row.getValue("length") != null) {
            Long lengthMillis = row.getLong("length");
            doc.setLength(Duration.ofMillis(lengthMillis));
        }
        doc.setArchived(row.getInteger("archived"));
        doc.setSlugName(row.getString("slug_name"));
        doc.setDescription(row.getString("description"));
        doc.setExpiresAt(row.getLocalDateTime("expires_at"));

        Uni<SoundFragment> uni = Uni.createFrom().item(doc);

        if (includeGenres) {
            uni = uni.chain(d -> loadGenres(d.getId()).onItem().transform(genres -> {
                d.setGenres(genres);
                return d;
            }));
        } else {
            doc.setGenres(List.of());
        }

        if (includeLabels) {
            uni = uni.chain(d -> loadLabels(d.getId()).onItem().transform(labels -> {
                d.setLabels(labels);
                return d;
            }));
        } else {
            doc.setLabels(List.of());
        }

        if (includeFiles) {
            String fileQuery = "SELECT id, reg_date, last_mod_date, parent_table, parent_id, archived, archived_date, storage_type, mime_type, slug_name, file_original_name, file_key FROM _files WHERE parent_table = '" + entityData.getTableName() + "' AND parent_id = $1 AND archived = 0 ORDER BY reg_date ASC";
            uni = uni.chain(soundFragment -> client.preparedQuery(fileQuery)
                    .execute(Tuple.of(soundFragment.getId()))
                    .onItem().transform(rowSet -> {
                        List<FileMetadata> files = new ArrayList<>();
                        for (Row fileRow : rowSet) {
                            FileMetadata fileMetadata = new FileMetadata();
                            fileMetadata.setId(fileRow.getLong("id"));
                            fileMetadata.setRegDate(fileRow.getLocalDateTime("reg_date").atZone(ZoneId.systemDefault()));
                            fileMetadata.setLastModifiedDate(fileRow.getLocalDateTime("last_mod_date").atZone(ZoneId.systemDefault()));
                            fileMetadata.setParentTable(fileRow.getString("parent_table"));
                            fileMetadata.setParentId(fileRow.getUUID("parent_id"));
                            fileMetadata.setArchived(fileRow.getInteger("archived"));
                            if (fileRow.getLocalDateTime("archived_date") != null)
                                fileMetadata.setArchivedDate(fileRow.getLocalDateTime("archived_date"));
                            fileMetadata.setFileStorageType(FileStorageType.valueOf(fileRow.getString("storage_type")));
                            fileMetadata.setMimeType(fileRow.getString("mime_type"));
                            fileMetadata.setSlugName(fileRow.getString("slug_name"));
                            fileMetadata.setFileOriginalName(fileRow.getString("file_original_name"));
                            fileMetadata.setFileKey(fileRow.getString("file_key"));
                            files.add(fileMetadata);
                        }
                        soundFragment.setFileMetadataList(files);
                        if (files.isEmpty()) markAsCorrupted(soundFragment.getId()).subscribe().with(r -> {}, e -> {});
                        return soundFragment;
                    }));
        } else {
            doc.setFileMetadataList(List.of());
        }

        return uni;
    }

    private Uni<List<UUID>> loadLabels(UUID soundFragmentId) {
        String sql = "SELECT label_id FROM kneobroadcaster__sound_fragment_labels WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("label_id"))
                .collect().asList();
    }


    private Uni<List<UUID>> loadGenres(UUID soundFragmentId) {
        String sql = "SELECT g.id FROM __genres g " +
                "JOIN kneobroadcaster__sound_fragment_genres sfg ON g.id = sfg.genre_id " +
                "WHERE sfg.sound_fragment_id = $1 ORDER BY g.identifier";

        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("id"))
                .collect().asList();
    }

    public Uni<Integer> markAsCorrupted(UUID uuid) {
        IUser user = SuperUser.build();
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), uuid)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException(
                                "User does not have edit permission", user.getUserName(), uuid));
                    }

                    String sql = String.format("UPDATE %s SET archived = -1, last_mod_date = $1, last_mod_user = $2 WHERE id = $3",
                            entityData.getTableName());
                    return client.preparedQuery(sql)
                            .execute(Tuple.of(ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime(), user.getId(), uuid))
                            .onItem().transform(SqlResult::rowCount);
                });
    }
}
