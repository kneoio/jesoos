package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PreDestroy;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractStreamPool<T extends ILiveStream> {

    protected final ConcurrentHashMap<String, T> pool = new ConcurrentHashMap<>();

    public Uni<T> get(String slugName) {
        return Uni.createFrom().item(pool.get(slugName));
    }

    public Collection<T> getSnapshot() {
        return new ArrayList<>(pool.values());
    }

    public Uni<T> stopAndRemove(String slugName) {
        getLogger().infof("Stopping and removing stream: %s", slugName);
        T stream = pool.remove(slugName);
        if (stream != null) {
            stream.setStatus(StreamStatus.OFF_LINE);
            onRemoved(slugName, stream);
            return Uni.createFrom().item(stream);
        }
        getLogger().warnf("Stream '%s' not found during stopAndRemove", slugName);
        return Uni.createFrom().nullItem();
    }

    protected abstract void onRemoved(String slugName, T stream);

    protected abstract Logger getLogger();

    @PreDestroy
    void cleanup() {
        getLogger().infof("%s cleanup: clearing %d streams", getClass().getSimpleName(), pool.size());
        new ArrayList<>(pool.entrySet()).forEach(e -> onRemoved(e.getKey(), e.getValue()));
        pool.clear();
    }
}
