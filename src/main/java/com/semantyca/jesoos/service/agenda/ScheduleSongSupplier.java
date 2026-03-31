package com.semantyca.jesoos.service.agenda;

import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScheduleSongSupplier {

    private final SoundFragmentRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    public ScheduleSongSupplier(SoundFragmentRepository repository) {
        this.repository = repository;
    }

    public Uni<List<SoundFragment>> getSongsForBrand(UUID brandId, PlaylistItemType type, int quantity) {
        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setType(List.of(type));
        return repository.findByFilter(brandId, filter, quantity)
                .map(fragments -> selectRandom(fragments, quantity));
    }

    public Uni<List<SoundFragment>> getSongsByQuery(UUID brandId, PlaylistRequest playlistRequest, int quantity) {
        SoundFragmentFilter filter = buildFilter(playlistRequest);
        return repository.findByFilter(brandId, filter, quantity)
                .map(fragments -> selectRandom(fragments, quantity));
    }

    public Uni<List<SoundFragment>> getSongsFromStaticList(List<UUID> soundFragmentIds, int quantity) {
        if (soundFragmentIds == null || soundFragmentIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return repository.findByIds(soundFragmentIds)
                .map(fragments -> selectRandom(fragments, quantity));
    }

    private List<SoundFragment> selectRandom(List<SoundFragment> fragments, int quantity) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        List<SoundFragment> shuffled = new ArrayList<>(fragments);
        Collections.shuffle(shuffled, secureRandom);
        if (quantity >= shuffled.size()) {
            return shuffled;
        }
        return shuffled.stream().limit(quantity).collect(Collectors.toList());
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
