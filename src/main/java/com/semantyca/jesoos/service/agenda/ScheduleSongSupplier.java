package com.semantyca.jesoos.service.agenda;

import com.semantyca.jesoos.model.stream.SharedSongEntry;
import com.semantyca.jesoos.model.stream.SongPool;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.soundfragment.SharedSoundFragmentService;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScheduleSongSupplier {
    private static final Logger LOGGER = Logger.getLogger(ScheduleSongSupplier.class);

    private final SoundFragmentRepository repository;
    private final SharedSoundFragmentService sharedSoundFragmentService;

    @Inject
    public ScheduleSongSupplier(SoundFragmentRepository repository, SharedSoundFragmentService sharedSoundFragmentService) {
        this.repository = repository;
        this.sharedSoundFragmentService = sharedSoundFragmentService;
    }

    public Uni<SongPool> getSongsForBrand(UUID brandId, PlaylistItemType type, int quantity, Set<UUID> excludeIds) {
        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setType(List.of(type));

        int newest = Math.max(1, (int) Math.ceil(quantity * 0.3));
        int oldest = Math.max(1, (int) Math.ceil(quantity * 0.4));
        int randomCount = Math.max(1, quantity - newest - oldest);

        Set<UUID> effective = (excludeIds != null) ? excludeIds : Set.of();

        long t0 = System.currentTimeMillis();
        LOGGER.infof("[getSongsForBrand] brand=%s type=%s quantity=%d excludeIds=%d", brandId, type, quantity, effective.size());

        return Uni.combine().all()
                .unis(
                        repository.findByFilter(brandId, filter, quantity, effective),
                        sharedSoundFragmentService.getForBrand(brandId, type, quantity, effective)
                )
                .asTuple()
                .map(tuple -> {
                    List<SoundFragment> all = tuple.getItem1();
                    List<SharedSongEntry> shared = tuple.getItem2();
                    LOGGER.infof("[getSongsForBrand] done in %dms, got %d songs, %d shared", System.currentTimeMillis() - t0, all.size(), shared.size());

                    int size = all.size();
                    int newestEnd = Math.min(newest, size);
                    int oldestStart = Math.max(newestEnd, size - oldest);

                    List<SoundFragment> newestList = all.subList(0, newestEnd);
                    List<SoundFragment> oldestList = all.subList(oldestStart, size);
                    List<SoundFragment> middle = new ArrayList<>(all.subList(newestEnd, oldestStart));
                    Collections.shuffle(middle);
                    List<SoundFragment> randomList = middle.subList(0, Math.min(randomCount, middle.size()));

                    Map<UUID, SoundFragment> merged = new LinkedHashMap<>();
                    newestList.forEach(s -> merged.put(s.getId(), s));
                    oldestList.forEach(s -> merged.putIfAbsent(s.getId(), s));
                    randomList.forEach(s -> merged.putIfAbsent(s.getId(), s));

                    Map<UUID, String> sharerMap = new HashMap<>();
                    for (SharedSongEntry entry : shared) {
                        UUID id = entry.soundFragment().getId();
                        merged.putIfAbsent(id, entry.soundFragment());
                        sharerMap.put(id, entry.sharerName());
                    }

                    List<SoundFragment> result = new ArrayList<>(merged.values());
                    Collections.shuffle(result);
                    return new SongPool(result, sharerMap);
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
