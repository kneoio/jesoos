package com.semantyca.jesoos.service.soundfragment;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.FileSecurityUtils;
import com.semantyca.jesoos.dto.BrandSoundFragmentDTO;
import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.dto.UploadFileDTO;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    protected SoundFragmentService(UserService userService, BrandService brandService) {
        super(userService);
        this.brandService = brandService;
        this.repository = null;
    }

    @Inject
    public SoundFragmentService(UserService userService, SoundFragmentRepository repository, BrandService brandService) {
        super(userService);
        this.repository = repository;
        this.brandService = brandService;
    }

    public Uni<Integer> getAllCount(final IUser user, final SoundFragmentFilter filter) {
        assert repository != null;
        return repository.getAllCount(user, filter);
    }

    public Uni<List<SoundFragment>> getByTypeAndBrand(PlaylistItemType type, UUID brandId) {
        assert repository != null;
        return repository.findByTypeAndBrand(type, brandId, 100, 0);
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

    public Uni<SoundFragment> findByArtistAndDate(String artist, java.time.LocalDateTime startOfDay, java.time.LocalDateTime endOfDay) {
        assert repository != null;
        return repository.findByArtistAndDate(artist, startOfDay, endOfDay);
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
}
