package com.semantyca.djinn.service.soundfragment;

import com.semantyca.djinn.dto.BrandSoundFragmentFlatDTO;
import com.semantyca.djinn.dto.SoundFragmentFilterDTO;
import com.semantyca.djinn.repository.soundfragment.SoundFragmentBrandRepository;
import com.semantyca.djinn.service.BrandService;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragmentFlat;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandSoundFragmentService {

    private final SoundFragmentBrandRepository repository;
    private final BrandService brandService;

    @Inject
    public BrandSoundFragmentService(SoundFragmentBrandRepository repository, BrandService brandService) {
        this.repository = repository;
        this.brandService = brandService;
    }

    public Uni<List<BrandSoundFragmentFlatDTO>> getBrandSoundFragmentsFlat(String brandName, int limit, int offset,
                                                                           SoundFragmentFilterDTO filterDTO, IUser user) {
        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    SoundFragmentFilter filter = toFilter(filterDTO);
                    UUID brandId = brand.getId();
                    return repository.findForBrandFlat(brandId, limit, offset, user, filter)
                            .onItem().transform(fragments -> {
                                if (fragments.isEmpty()) {
                                    return Collections.<BrandSoundFragmentFlatDTO>emptyList();
                                }
                                return fragments.stream()
                                        .map(this::mapToDTO)
                                        .collect(Collectors.toList());
                            });
                });
    }

    public Uni<Integer> getBrandSoundFragmentsCount(String brandName, SoundFragmentFilterDTO filterDTO, IUser user) {
        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    SoundFragmentFilter filter = toFilter(filterDTO);
                    UUID brandId = brand.getId();
                    return repository.findForBrandCount(brandId, user, filter);
                });
    }

    private BrandSoundFragmentFlatDTO mapToDTO(BrandSoundFragmentFlat flat) {
        BrandSoundFragmentFlatDTO dto = new BrandSoundFragmentFlatDTO();
        dto.setId(flat.getId());
        dto.setDefaultBrandId(flat.getDefaultBrandId());
        dto.setPlayedByBrandCount(flat.getPlayedByBrandCount());
        dto.setRatedByBrandCount(flat.getRatedByBrandCount());
        dto.setLastTimePlayedByBrand(flat.getPlayedTime());
        dto.setTitle(flat.getTitle());
        dto.setArtist(flat.getArtist());
        dto.setAlbum(flat.getAlbum());
        dto.setSource(flat.getSource());
        dto.setLabels(flat.getLabels());
        dto.setGenres(flat.getGenres());
        dto.setRepresentedInBrands(flat.getRepresentedInBrands());
        return dto;
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
}
