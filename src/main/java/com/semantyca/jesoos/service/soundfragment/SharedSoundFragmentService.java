package com.semantyca.jesoos.service.soundfragment;

import com.semantyca.jesoos.model.stream.SharedSongEntry;
import com.semantyca.jesoos.repository.soundfragment.SharedSoundFragmentRepository;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
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

@ApplicationScoped
public class SharedSoundFragmentService {

    private final SharedSoundFragmentRepository repository;

    @Inject
    public SharedSoundFragmentService(SharedSoundFragmentRepository repository) {
        this.repository = repository;
    }

    public Uni<List<SharedSongEntry>> getForBrand(UUID brandId, PlaylistItemType type, int quantity, Set<UUID> excludeIds) {
        int newest = Math.max(1, (int) Math.ceil(quantity * 0.4));
        int random = Math.max(1, quantity - newest);

        return Uni.combine().all()
                .unis(
                        repository.findByBrand(brandId, type, newest, excludeIds),
                        repository.findByBrandRandom(brandId, type, random, excludeIds)
                )
                .asTuple()
                .map(tuple -> {
                    Map<UUID, SharedSongEntry> merged = new LinkedHashMap<>();
                    tuple.getItem1().forEach(e -> merged.put(e.soundFragment().getId(), e));
                    tuple.getItem2().forEach(e -> merged.putIfAbsent(e.soundFragment().getId(), e));
                    List<SharedSongEntry> result = new ArrayList<>(merged.values());
                    Collections.shuffle(result);
                    return result;
                });
    }
}
