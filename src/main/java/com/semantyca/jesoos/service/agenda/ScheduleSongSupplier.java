package com.semantyca.jesoos.service.agenda;

import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScheduleSongSupplier {

    private final SoundFragmentRepository repository;

    @Inject
    public ScheduleSongSupplier(SoundFragmentRepository repository) {
        this.repository = repository;
    }

    public Uni<List<SoundFragment>> getSongsForBrand(UUID brandId, PlaylistItemType type, int quantity) {
        return getSongsForBrand(brandId, type, quantity, Set.of());
    }

    public Uni<List<SoundFragment>> getSongsForBrand(UUID brandId, PlaylistItemType type, int quantity, Set<UUID> excludeIds) {
        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setType(List.of(type));

        int newest = Math.max(1, (int) Math.ceil(quantity * 0.3));
        int oldest = Math.max(1, (int) Math.ceil(quantity * 0.4));
        int random = Math.max(1, quantity - newest - oldest);

        Set<UUID> effective = (excludeIds != null) ? excludeIds : Set.of();

        return Uni.combine().all()
                .unis(
                        repository.findByFilter(brandId, filter, newest, effective),
                        repository.findByFilterOldest(brandId, filter, oldest, effective),
                        repository.findByFilterRandom(brandId, filter, random, effective)
                )
                .asTuple()
                .map(tuple -> {
                    Map<UUID, SoundFragment> merged = new LinkedHashMap<>();
                    tuple.getItem1().forEach(s -> merged.put(s.getId(), s));
                    tuple.getItem2().forEach(s -> merged.putIfAbsent(s.getId(), s));
                    tuple.getItem3().forEach(s -> merged.putIfAbsent(s.getId(), s));
                    List<SoundFragment> result = new ArrayList<>(merged.values());
                    Collections.shuffle(result);
                    return result;
                });
    }

    public Uni<List<SoundFragment>> getSongsByQuery(UUID brandId, PlaylistRequest playlistRequest, int quantity) {
        SoundFragmentFilter filter = buildFilter(playlistRequest);
        return repository.findByFilter(brandId, filter, quantity)
                .map(fragments -> limitQuantity(fragments, quantity));
    }

    public Uni<List<SoundFragment>> getSongsFromStaticList(UUID brandId, List<UUID> soundFragmentIds, int quantity) {
        if (soundFragmentIds == null || soundFragmentIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return repository.findByIdsForBrand(brandId, soundFragmentIds)
                .map(fragments -> limitQuantity(fragments, quantity));
    }

    /** Preserves repository order (boost, then fewer plays for the brand). */
    private List<SoundFragment> limitQuantity(List<SoundFragment> fragments, int quantity) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        if (quantity <= 0 || quantity >= fragments.size()) {
            return new ArrayList<>(fragments);
        }
        return fragments.stream().limit(quantity).collect(Collectors.toList());
    }

    private SoundFragmentFilter buildFilter(PlaylistRequest playlistRequest) {
        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setGenre(playlistRequest.getGenres());
        filter.setLabels(playlistRequest.getLabels());
        filter.setType(playlistRequest.getType());
        filter.setSource(playlistRequest.getSource());
        filter.setSearchTerm(playlistRequest.getSearchTerm());
        return filter;
    }
}
