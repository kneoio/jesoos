package com.semantyca.jesoos.service.agenda;

import com.semantyca.jesoos.model.stream.SharedSongEntry;
import com.semantyca.jesoos.model.stream.SongPool;
import com.semantyca.jesoos.model.stream.SongSourceScope;
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

    public Uni<SongPool> getSongsRandomly(SongSourceScope scope, PlaylistItemType type, int quantity, Set<UUID> excludeIds) {
        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setType(List.of(type));

        int newest = Math.max(1, (int) Math.ceil(quantity * 0.3));
        int oldest = Math.max(1, (int) Math.ceil(quantity * 0.4));
        int randomCount = Math.max(1, quantity - newest - oldest);

        Set<UUID> effective = (excludeIds != null) ? excludeIds : Set.of();

        long t0 = System.currentTimeMillis();
        LOGGER.infof("[getSongsForBrand] scope=%s type=%s quantity=%d excludeIds=%d", scope, type, quantity, effective.size());

        Uni<List<SoundFragment>> newestUni;
        Uni<List<SoundFragment>> oldestUni;
        Uni<List<SoundFragment>> randomUni;
        Uni<List<SharedSongEntry>> sharedUni;

        switch (scope) {
            case SongSourceScope.BrandScope brandScope -> {
                UUID brandId = brandScope.brandId();
                newestUni = repository.findByFilter(brandId, filter, newest, effective);
                oldestUni = repository.findByFilterOldest(brandId, filter, oldest, effective);
                randomUni = repository.findByFilterRandom(brandId, filter, randomCount, effective);
                sharedUni = sharedSoundFragmentService.getForBrand(brandId, type, quantity, effective);
            }
            case SongSourceScope.OwnerScope ownerScope -> {
                long userId = ownerScope.userId();
                newestUni = repository.findByOwner(userId, filter, newest, effective);
                oldestUni = repository.findByOwnerOldest(userId, filter, oldest, effective);
                randomUni = repository.findByOwnerRandom(userId, filter, randomCount, effective);
                sharedUni = Uni.createFrom().item(List.of());
            }
        }

        return Uni.combine().all()
                .unis(newestUni, oldestUni, randomUni, sharedUni)
                .asTuple()
                .chain(tuple -> {
                    List<SoundFragment> newestList = tuple.getItem1();
                    List<SoundFragment> oldestList = tuple.getItem2();
                    List<SoundFragment> randomList = tuple.getItem3();
                    List<SharedSongEntry> shared = tuple.getItem4();
                    LOGGER.infof("[getSongsForBrand] done in %dms, got newest=%d oldest=%d random=%d, %d shared",
                            System.currentTimeMillis() - t0, newestList.size(), oldestList.size(), randomList.size(), shared.size());

                    Map<UUID, SoundFragment> merged = new LinkedHashMap<>();
                    newestList.forEach(s -> merged.putIfAbsent(s.getId(), s));
                    oldestList.forEach(s -> merged.putIfAbsent(s.getId(), s));
                    randomList.forEach(s -> merged.putIfAbsent(s.getId(), s));

                    Map<UUID, SongPool.SharedMeta> sharedInfo = new HashMap<>();
                    for (SharedSongEntry entry : shared) {
                        UUID id = entry.soundFragment().getId();
                        merged.putIfAbsent(id, entry.soundFragment());
                        sharedInfo.put(id, new SongPool.SharedMeta(entry.sharerName(), entry.sourceUserEmail(), entry.priority()));
                    }

                    List<SoundFragment> result = new ArrayList<>(merged.values());
                    return floatPriorityToFront(result, sharedInfo);
                });
    }

    /**
     * Pulls fragments labeled "new" to the front of the freshly assembled pool (ahead of the
     * normal shuffle), so the next agenda rebuild plays a new contribution as soon as possible. The
     * label is cleared right after selection so a later rebuild doesn't re-float the same fragment.
     * The contributor is emailed separately, in {@code IntroTtsGenerator}, only if/when their song
     * actually gets a spoken DJ intro — not here at build time.
     */
    private Uni<SongPool> floatPriorityToFront(List<SoundFragment> pool, Map<UUID, SongPool.SharedMeta> sharedInfo) {
        List<SoundFragment> priority = new ArrayList<>();
        List<SoundFragment> rest = new ArrayList<>();
        for (SoundFragment sf : pool) {
            SongPool.SharedMeta meta = sharedInfo.get(sf.getId());
            if (meta != null && meta.priority()) {
                priority.add(sf);
            } else {
                rest.add(sf);
            }
        }
        Collections.shuffle(rest);
        List<SoundFragment> result = new ArrayList<>(priority.size() + rest.size());
        result.addAll(priority);
        result.addAll(rest);

        if (priority.isEmpty()) {
            return Uni.createFrom().item(new SongPool(result, sharedInfo));
        }
        List<Uni<Void>> clears = priority.stream()
                .map(sf -> sharedSoundFragmentService.clearPriorityLabel(sf.getId()))
                .toList();
        return Uni.join().all(clears).andCollectFailures()
                .replaceWith(new SongPool(result, sharedInfo));
    }

    public Uni<List<SoundFragment>> getSongsByQuery(SongSourceScope scope, PlaylistRequest playlistRequest, int quantity, Set<UUID> excludeIds) {
        SoundFragmentFilter filter = buildFilter(playlistRequest);
        Set<UUID> effective = (excludeIds != null) ? excludeIds : Set.of();
        Uni<List<SoundFragment>> fragmentsUni = switch (scope) {
            case SongSourceScope.BrandScope brandScope -> repository.findByFilter(brandScope.brandId(), filter, quantity, effective);
            case SongSourceScope.OwnerScope ownerScope -> repository.findByOwner(ownerScope.userId(), filter, quantity, effective);
        };
        return fragmentsUni.map(fragments -> {
            List<SoundFragment> result = limitQuantity(fragments, quantity);
            Collections.shuffle(result);
            return result;
        });
    }

    /**
     * Any song in scope, ignoring the scene's own criteria. Fills slots the scene's filter could not
     * match, so a narrow filter yields unmatched songs rather than a repeated one.
     * <p>
     * Includes songs shared to the brand ("received"), same as {@link #getSongsRandomly}. The shared
     * catalog can only be narrowed by type, not by genre/label — but that costs nothing here, because
     * widening drops the scene's criteria anyway. Owner-scoped streams have no shared catalog.
     */
    public Uni<SongPool> getAnySongs(SongSourceScope scope, int quantity, Set<UUID> excludeIds) {
        if (quantity <= 0) {
            return Uni.createFrom().item(new SongPool(List.of(), Map.of()));
        }
        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setType(List.of(PlaylistItemType.SONG));
        Set<UUID> effective = (excludeIds != null) ? excludeIds : Set.of();

        Uni<List<SoundFragment>> ownUni;
        Uni<List<SharedSongEntry>> sharedUni;
        switch (scope) {
            case SongSourceScope.BrandScope brandScope -> {
                ownUni = repository.findByFilterRandom(brandScope.brandId(), filter, quantity, effective);
                sharedUni = sharedSoundFragmentService.getForBrand(brandScope.brandId(), PlaylistItemType.SONG, quantity, effective);
            }
            case SongSourceScope.OwnerScope ownerScope -> {
                ownUni = repository.findByOwnerRandom(ownerScope.userId(), filter, quantity, effective);
                sharedUni = Uni.createFrom().item(List.of());
            }
        }

        return Uni.combine().all().unis(ownUni, sharedUni).asTuple()
                .chain(tuple -> {
                    Map<UUID, SoundFragment> merged = new LinkedHashMap<>();
                    tuple.getItem1().forEach(sf -> merged.putIfAbsent(sf.getId(), sf));

                    Map<UUID, SongPool.SharedMeta> sharedInfo = new HashMap<>();
                    for (SharedSongEntry entry : tuple.getItem2()) {
                        UUID id = entry.soundFragment().getId();
                        merged.putIfAbsent(id, entry.soundFragment());
                        sharedInfo.put(id, new SongPool.SharedMeta(entry.sharerName(), entry.sourceUserEmail(), entry.priority()));
                    }

                    LOGGER.infof("[getAnySongs] widening pool: %d own + %d shared -> %d distinct",
                            tuple.getItem1().size(), tuple.getItem2().size(), merged.size());
                    return floatPriorityToFront(new ArrayList<>(merged.values()), sharedInfo);
                });
    }

    /**
     * A static list is an explicit curation: every pinned fragment is returned, in the pinned order.
     */
    public Uni<List<SoundFragment>> getSongsFromStaticList(SongSourceScope scope, List<UUID> soundFragmentIds) {
        if (soundFragmentIds == null || soundFragmentIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return switch (scope) {
            case SongSourceScope.BrandScope brandScope -> repository.findByIdsForBrand(brandScope.brandId(), soundFragmentIds);
            case SongSourceScope.OwnerScope ownerScope -> repository.findByIdsForOwner(ownerScope.userId(), soundFragmentIds);
        };
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
