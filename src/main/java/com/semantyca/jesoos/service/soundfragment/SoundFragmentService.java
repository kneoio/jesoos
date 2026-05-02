package com.semantyca.jesoos.service.soundfragment;

import com.semantyca.core.model.FileMetadata;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.FileSecurityUtils;
import com.semantyca.core.util.WebHelper;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.BrandSoundFragmentDTO;
import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.dto.UploadFileDTO;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentService extends AbstractService<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = Logger.getLogger(SoundFragmentRepository.class);
    private final SoundFragmentRepository repository;
    private final BrandService brandService;
    private final String uploadDir;
    @Inject
    public SoundFragmentService(UserService userService, JesoosConfig config, SoundFragmentRepository repository, BrandService brandService) {
        super(userService);
        this.repository = repository;
        this.brandService = brandService;
        uploadDir = config.getPathUploads() + "/chat-upload-controller";
    }

    public Uni<Integer> getAllCount(final IUser user, final SoundFragmentFilter filter) {
        assert repository != null;
        return repository.getAllCount(user, filter);
    }

    public Uni<SoundFragment> getById(UUID uuid) {
        assert repository != null;
        return repository.findById(uuid, SuperUser.ID, false, false);
    }

    public Uni<List<SoundFragment>> getByTypeAndBrand(PlaylistItemType type, UUID brandId) {
        assert repository != null;
        return repository.findByTypeAndBrand(type, brandId, 100, 0);
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragmentsForAiWithFilter(String brandName, String keyword, SoundFragmentFilter filter, int limit, int offset) {
        assert repository != null;
        assert brandService != null;

        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    return repository.findForBrandWithFilter(brandId, keyword, filter, limit, offset, SuperUser.build())
                            .chain(fragments -> {
                                if (fragments.isEmpty()) {
                                    return Uni.createFrom().item(Collections.<BrandSoundFragmentDTO>emptyList());
                                }
                                List<Uni<BrandSoundFragmentDTO>> unis = fragments.stream()
                                        .map(this::mapToBrandSoundFragmentDTO)
                                        .collect(Collectors.toList());
                                return Uni.join().all(unis).andFailFast();
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.error("Failed to search fragments for brand: %s", brandName, failure);
                    return Uni.<List<BrandSoundFragmentDTO>>createFrom().failure(failure);
                });
    }

    public Uni<io.vertx.core.json.JsonObject> getBrandCatalogSummary(String brandName) {
        assert repository != null;
        assert brandService != null;
        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    return repository.getBrandCatalogSummary(radioStation.getId());
                });
    }

    private Uni<BrandSoundFragmentDTO> mapToBrandSoundFragmentDTO(BrandSoundFragment doc) {
        return mapToDTO(doc.getSoundFragment(), false, null)
                .onItem().transform(soundFragmentDTO -> {
                    BrandSoundFragmentDTO dto = new BrandSoundFragmentDTO();
                    dto.setId(doc.getId());
                    dto.setSoundFragmentDTO(soundFragmentDTO);
                    dto.setPlayedByBrandCount(doc.getPlayedByBrandCount());
                    dto.setRatedByBrandCount(doc.getRatedByBrandCount());
                    dto.setLastTimePlayedByBrand(doc.getPlayedTime());
                    dto.setDefaultBrandId(doc.getDefaultBrandId());
                    dto.setRepresentedInBrands(doc.getRepresentedInBrands());
                    return dto;
                });
    }


    private Uni<SoundFragmentDTO> mapToDTO(SoundFragment doc, boolean exposeFileUrl, List<UUID> representedInBrands) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().onItem().transform(tuple -> {
            String author = tuple.getItem1();
            String lastModifier = tuple.getItem2();
            List<UploadFileDTO> files = new ArrayList<>();

            if (exposeFileUrl && doc.getFileMetadataList() != null) {
                doc.getFileMetadataList().forEach(meta -> {
                    String safeFileName = FileSecurityUtils.sanitizeFilename(meta.getFileOriginalName());
                    UploadFileDTO fileDto = new UploadFileDTO();
                    fileDto.setId(meta.getSlugName());
                    fileDto.setName(safeFileName);
                    fileDto.setStatus("finished");
                    fileDto.setUrl("/soundfragments/files/" + doc.getId() + "/" + meta.getSlugName());
                    fileDto.setPercentage(100);
                    files.add(fileDto);
                });
            }

            SoundFragmentDTO dto = new SoundFragmentDTO();
            dto.setId(doc.getId());
            dto.setAuthor(author);
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(lastModifier);
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setSource(doc.getSource());
            dto.setStatus(doc.getStatus());
            dto.setType(doc.getType());
            dto.setTitle(doc.getTitle());
            dto.setArtist(doc.getArtist());
            dto.setGenres(doc.getGenres());
            dto.setLabels(doc.getLabels());
            dto.setAlbum(doc.getAlbum());
            dto.setLength(doc.getLength());
            dto.setDescription(doc.getDescription());
            dto.setExpiresAt(doc.getExpiresAt());
            dto.setUploadedFiles(files);
            dto.setRepresentedInBrands(representedInBrands);
            return dto;
        });
    }

    public Uni<SoundFragmentDTO> upsert(String id, SoundFragmentDTO dto, IUser user, LanguageCode code) {
        SoundFragment entity = buildEntity(dto);
        //entity.setArchived(10);   //TODO it should be depend on settings
        List<FileMetadata> fileMetadataList = new ArrayList<>();
        if (dto.getNewlyUploaded() != null && !dto.getNewlyUploaded().isEmpty()) {
            for (String fileName : dto.getNewlyUploaded()) {
                String safeFileName;
                try {
                    safeFileName = FileSecurityUtils.sanitizeFilename(fileName);
                } catch (SecurityException e) {
                    LOGGER.errorf("Security violation: Unsafe filename '%s' from user: %s", fileName, user.getUserName());
                    return Uni.createFrom().failure(new IllegalArgumentException("Invalid filename: " + fileName));
                }

                String tempFolderName = "";
                FileMetadata fileMetadata = new FileMetadata();
                if (id == null || "new".equalsIgnoreCase(id)) {
                    tempFolderName = "temp";
                } else {
                    try {
                        UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        LOGGER.errorf("Security violation: Invalid entity ID '%s' from user: %s", id, user.getUserName());
                        return Uni.createFrom().failure(new IllegalArgumentException("Invalid entity ID"));
                    }
                }

                Path baseDir = Paths.get(uploadDir, user.getUserName(), tempFolderName);
                Path secureFilePath;
                try {
                    secureFilePath = FileSecurityUtils.secureResolve(baseDir, safeFileName);
                } catch (SecurityException e) {
                    LOGGER.errorf("Security violation: Path traversal attempt by user %s with filename %s",
                            user.getUserName(), fileName);
                    return Uni.createFrom().failure(new SecurityException("Invalid file path"));
                }

                LOGGER.infof(
                        "upsert upload resolution: id=%s, user=%s, uploadDir=%s, baseDir=%s, fileName=%s, resolvedPath=%s",
                        id,
                        user.getUserName(),
                        uploadDir,
                        baseDir.toAbsolutePath(),
                        safeFileName,
                        secureFilePath.toAbsolutePath()
                );

                if (!Files.exists(secureFilePath)) {
                    LOGGER.errorf(
                            "File not found at expected secure path: %s for user: %s (baseDirExists=%s, parentExists=%s)",
                            secureFilePath,
                            user.getUserName(),
                            Files.exists(baseDir),
                            Files.exists(secureFilePath.getParent())
                    );
                    return Uni.createFrom().failure(new IllegalArgumentException("Something happen wrong with the uploaded file"));
                    // continue;
                }

                fileMetadata.setFilePath(secureFilePath);
                fileMetadata.setFileOriginalName(safeFileName);
                fileMetadata.setSlugName(WebHelper.generateSlug(entity.getArtist(), entity.getTitle()));
                fileMetadataList.add(fileMetadata);
            }
        }

        entity.setFileMetadataList(fileMetadataList);

        if ("new".equalsIgnoreCase(id) || id == null) {
            if (entity.getSource() == null) {
                entity.setSource(SourceType.CONTRIBUTION);
            }
            return repository.insert(entity, dto.getRepresentedInBrands(), dto.getRlsActions(), user)
                    .chain(doc -> moveFilesForNewEntity(doc, fileMetadataList, user))
                    .chain(doc -> mapToDTO(doc, true, null))
                    .onFailure().invoke(failure -> LOGGER.warnf("Entity creation failed for user: %s", user.getUserName()));
        } else {
            return repository.update(UUID.fromString(id), entity, dto.getRepresentedInBrands(), user)
                    .chain(doc -> mapToDTO(doc, true, null))
                    .onFailure().invoke(failure -> LOGGER.warnf("Entity update failed for user: %s, entity: %s", user.getUserName(), id));
        }
    }

    private Uni<SoundFragment> moveFilesForNewEntity(SoundFragment doc, List<FileMetadata> fileMetadataList, IUser user) {
        if (fileMetadataList.isEmpty()) {
            return Uni.createFrom().item(doc);
        }

        try {
            Path userBaseDir = Paths.get(uploadDir, user.getUserName());
            Path tempDir = userBaseDir.resolve("temp");
            Path entityDir = userBaseDir.resolve(doc.getId().toString());

            if (Files.exists(tempDir)) {
                if (!Files.exists(entityDir)) {
                    Files.createDirectories(entityDir);
                }

                for (FileMetadata meta : fileMetadataList) {
                    String safeFileName = FileSecurityUtils.sanitizeFilename(meta.getFileOriginalName());

                    Path tempFile = FileSecurityUtils.secureResolve(tempDir, safeFileName);
                    Path entityFile = FileSecurityUtils.secureResolve(entityDir, safeFileName);

                    if (!FileSecurityUtils.isPathWithinBase(tempDir, tempFile) ||
                            !FileSecurityUtils.isPathWithinBase(entityDir, entityFile)) {
                        LOGGER.errorf("Security violation: Invalid file paths during move operation for user: %s", user.getUserName());
                        return Uni.createFrom().failure(new SecurityException("Invalid file paths"));
                    }

                    if (Files.exists(tempFile)) {
                        Files.move(tempFile, entityFile, StandardCopyOption.REPLACE_EXISTING);
                        meta.setFilePath(entityFile);
                        LOGGER.debugf("Securely moved file from %s to %s for user: %s", tempFile, entityFile, user.getUserName());
                    }
                }
            }

            return Uni.createFrom().item(doc);
        } catch (SecurityException e) {
            LOGGER.errorf("Security violation during file move for entity: %s, user: %s", doc.getId(), e);
            return Uni.createFrom().failure(e);
        } catch (Exception e) {
            LOGGER.errorf("Failed to move files for entity: %s, user: %s", doc.getId(), user.getUserName(), e);
            return Uni.createFrom().failure(e);
        }
    }


    private SoundFragment buildEntity(SoundFragmentDTO dto) {
        SoundFragment doc = new SoundFragment();
        doc.setStatus(dto.getStatus());
        doc.setType(dto.getType());
        doc.setSource(dto.getSource());
        doc.setTitle(dto.getTitle());
        doc.setArtist(dto.getArtist());
        doc.setGenres(dto.getGenres());
        doc.setLabels(dto.getLabels());
        doc.setAlbum(dto.getAlbum());
        doc.setLength(dto.getLength());
        doc.setDescription(dto.getDescription());
        doc.setExpiresAt(dto.getExpiresAt());
        doc.setSlugName(WebHelper.generateSlug(dto.getTitle(), dto.getArtist()));
        return doc;
    }

}
