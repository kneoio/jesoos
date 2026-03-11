package com.semantyca.jesoos.service.soundfragment;

import com.semantyca.jesoos.dto.BrandSoundFragmentFlatDTO;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentBrandRepository;
import com.semantyca.jesoos.service.BrandService;
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
                                                                           SoundFragmentFilter filter, IUser user) {
        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
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

    public Uni<Integer> getBrandSoundFragmentsCount(String brandName, SoundFragmentFilter filter, IUser user) {
        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
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
}
