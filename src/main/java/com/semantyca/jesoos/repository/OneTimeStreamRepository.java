package com.semantyca.jesoos.repository;

import com.semantyca.jesoos.model.stream.OneTimeStream;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
@ApplicationScoped
public class OneTimeStreamRepository {

    private final Map<String, OneTimeStream> inMemoryRepository = new HashMap<>();

    public Uni<OneTimeStream> getBySlugName(String slugName) {
        return Uni.createFrom().item(inMemoryRepository.get(slugName));
    }

    public Uni<OneTimeStream> findById(String streamId) {
        return Uni.createFrom().item(
                inMemoryRepository.values().stream()
                        .filter(s -> s.getStreamId() != null && s.getStreamId().equals(streamId))
                        .findFirst()
                        .orElse(null)
        );
    }

    public Uni<Void> insert(OneTimeStream doc) {
        inMemoryRepository.put(doc.getSlugName(), doc);
        return Uni.createFrom().voidItem();
    }

    public Uni<OneTimeStream> update(String streamId, OneTimeStream doc) {
        OneTimeStream existing = inMemoryRepository.values().stream()
                .filter(s -> s.getStreamId() != null && s.getStreamId().equals(streamId))
                .findFirst()
                .orElse(null);
        
        if (existing != null) {
            inMemoryRepository.remove(existing.getSlugName());
            doc.setStreamId(streamId);
            inMemoryRepository.put(doc.getSlugName(), doc);
            return Uni.createFrom().item(doc);
        }
        
        return Uni.createFrom().failure(new RuntimeException("Stream not found"));
    }

    public Uni<List<OneTimeStream>> getAll(int limit, int offset) {
        List<OneTimeStream> all = new ArrayList<>(inMemoryRepository.values());
        int fromIndex = Math.min(offset, all.size());
        int toIndex = Math.min(offset + limit, all.size());
        return Uni.createFrom().item(all.subList(fromIndex, toIndex));
    }

    public Uni<Integer> getAllCount() {
        return Uni.createFrom().item(inMemoryRepository.size());
    }

    public Uni<Void> delete(String streamId) {
        inMemoryRepository.values().stream()
                .filter(s -> s.getStreamId().equals(streamId))
                .findFirst()
                .ifPresent(s -> inMemoryRepository.remove(s.getSlugName()));
        return Uni.createFrom().voidItem();
    }
}
