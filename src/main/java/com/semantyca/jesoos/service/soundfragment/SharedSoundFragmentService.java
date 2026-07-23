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

    public Uni<Void> shareContribution(UUID soundFragmentId, UUID targetBrandId, long sourceUserId,
                                        String sourceUserName, String sourceUserEmail) {
        return repository.shareContribution(soundFragmentId, targetBrandId, sourceUserId, sourceUserName, sourceUserEmail);
    }

    public Uni<Void> addPriorityLabel(UUID soundFragmentId) {
        return repository.addPriorityLabel(soundFragmentId);
    }

    public Uni<Void> clearPriorityLabel(UUID soundFragmentId) {
        return repository.clearPriorityLabel(soundFragmentId);
    }

    public Uni<List<SharedSongEntry>> getPendingPriority(UUID brandId, PlaylistItemType type) {
        return repository.findPendingPriority(brandId, type, 5);
    }

    public Uni<List<SharedSongEntry>> getForBrand(UUID brandId, PlaylistItemType type, int quantity, Set<UUID> excludeIds) {
        int newest = Math.max(1, (int) Math.ceil(quantity * 0.4));
        int random = Math.max(1, quantity - newest);

        return Uni.combine().all()
                .unis(
                        repository.findByBrand(brandId, type, newest, excludeIds),
                        repository.findByBrandRandom(brandId, type, random, excludeIds),
                        repository.findPendingPriority(brandId, type, 5)
                )
                .asTuple()
                .map(tuple -> {
                    Map<UUID, SharedSongEntry> merged = new LinkedHashMap<>();
                    // Priority-labeled fragments go in first and unconditionally: the newest/random
                    // queries below rank by boost/date, not by the "new" label, so a priority song
                    // can lose the draw and never reach the pool at all if the catalog is large.
                    tuple.getItem3().forEach(e -> merged.put(e.soundFragment().getId(), e));
                    tuple.getItem1().forEach(e -> merged.putIfAbsent(e.soundFragment().getId(), e));
                    tuple.getItem2().forEach(e -> merged.putIfAbsent(e.soundFragment().getId(), e));
                    List<SharedSongEntry> result = new ArrayList<>(merged.values());
                    Collections.shuffle(result);
                    return result;
                });
    }
}
