package com.semantyca.djinn.service.soundfragment;

import com.semantyca.core.service.maintenance.LocalFileCleanupService;
import com.semantyca.djinn.config.DjinnConfig;
import com.semantyca.djinn.dto.BrandSoundFragmentDTO;
import com.semantyca.djinn.dto.SoundFragmentDTO;
import com.semantyca.djinn.dto.SoundFragmentFilterDTO;
import com.semantyca.djinn.dto.UploadFileDTO;
import com.semantyca.djinn.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.djinn.service.BrandService;
import com.semantyca.djinn.service.RefService;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.core.util.FileSecurityUtils;
import io.kneo.core.util.WebHelper;
import io.kneo.officeframe.service.GenreService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentService extends AbstractService<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentService.class);

    private final SoundFragmentRepository repository;
    private final BrandService brandService;
    private final GenreService genreService;
    private final LocalFileCleanupService localFileCleanupService;
    private final RefService refService;
    private String uploadDir;
    Validator validator;

    protected SoundFragmentService(UserService userService, GenreService genreService) {
        super(userService);
        this.genreService = genreService;
        this.localFileCleanupService = null;
        this.repository = null;
        this.brandService = null;
        this.refService = null;
    }

    @Inject
    public SoundFragmentService(UserService userService,
                                BrandService brandService, GenreService genreService,
                                LocalFileCleanupService localFileCleanupService,
                                Validator validator,
                                SoundFragmentRepository repository,
                                DjinnConfig config,
                                RefService refService) {
        super(userService);
        this.genreService = genreService;
        this.localFileCleanupService = localFileCleanupService;
        this.validator = validator;
        this.repository = repository;
        this.brandService = brandService;
        this.refService = refService;
        uploadDir = config.getPathUploads() + "/sound-fragments-controller";
    }

    public Uni<List<SoundFragmentDTO>> getAllDTO(final int limit, final int offset, final IUser user, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        SoundFragmentFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<SoundFragmentDTO>> unis = list.stream()
                                .map(doc -> mapToDTO(doc, false, null))
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        SoundFragmentFilter filter = toFilter(filterDTO);
        return repository.getAllCount(user, filter);
    }

    public Uni<List<SoundFragment>> getByTypeAndBrand(PlaylistItemType type, UUID brandId) {
        assert repository != null;
        return repository.findByTypeAndBrand(type, brandId, 100, 0);
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

    private SoundFragment buildEntity(SoundFragmentDTO dto) {
        SoundFragment doc = new SoundFragment();
        doc.setStatus(dto.getStatus());
        doc.setType(dto.getType());
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

    private SoundFragmentFilter toFilter(SoundFragmentFilterDTO dto) {
        if (dto == null) {
            return null;
        }

        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setActivated(dto.isActivated());
        filter.setGenre(dto.getGenres());
        filter.setLabels(dto.getLabels());
        filter.setSource(dto.getSources());
        filter.setType(dto.getTypes());
        filter.setSearchTerm(dto.getSearchTerm());

        return filter;
    }

    public Uni<SoundFragment> findByArtistAndDate(String artist, java.time.LocalDateTime startOfDay, java.time.LocalDateTime endOfDay) {
        assert repository != null;
        return repository.findByArtistAndDate(artist, startOfDay, endOfDay);
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragmentsBySimilarity(String brandName, String keyword, int limit, int offset) {
        assert repository != null;
        assert brandService != null;

        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    return repository.getForBrandBySimilarity(brandId, keyword, limit, offset, false, SuperUser.build())
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
                    LOGGER.error("Failed to similarity-search fragments for brand: {}", brandName, failure);
                    return Uni.<List<BrandSoundFragmentDTO>>createFrom().failure(failure);
                });
    }

}
