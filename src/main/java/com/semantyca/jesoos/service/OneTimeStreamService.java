package com.semantyca.jesoos.service;

import com.semantyca.core.model.user.IUser;
import com.semantyca.jesoos.model.stream.OneTimeStream;
import com.semantyca.jesoos.repository.OneTimeStreamRepository;
import com.semantyca.jesoos.service.live.OneTimeStreamPool;
import com.semantyca.jesoos.service.live.OtsStreamScheduler;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OneTimeStreamService {
    private static final Logger LOGGER = Logger.getLogger(OneTimeStreamService.class);

    private final BrandService brandService;
    private final ScriptService scriptService;
    private final OneTimeStreamRepository repository;
    private final OneTimeStreamPool pool;
    private final OtsStreamScheduler otsStreamScheduler;

    @Inject
    public OneTimeStreamService(BrandService brandService, ScriptService scriptService,
                                OneTimeStreamRepository repository,
                                OneTimeStreamPool pool, OtsStreamScheduler otsStreamScheduler) {
        this.brandService = brandService;
        this.scriptService = scriptService;
        this.repository = repository;
        this.pool = pool;
        this.otsStreamScheduler = otsStreamScheduler;
    }

    public Uni<OneTimeStream> run(String brandSlugName, UUID scriptId, Map<String, Object> userVariables, IUser user) {
        return brandService.getBySlugName(brandSlugName)
                .chain(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(new RuntimeException("Brand not found: " + brandSlugName));
                    }
                    return scriptService.getById(scriptId, user)
                            .chain(script -> {
                                if (script == null) {
                                    return Uni.createFrom().failure(new RuntimeException("Script not found: " + scriptId));
                                }
                                Map<String, Object> vars = userVariables != null ? userVariables : Map.of();
                                OneTimeStream stream = new OneTimeStream(brand, script, vars);
                                stream.setStatus(StreamStatus.PENDING);
                                return repository.insert(stream)
                                        .invoke(() -> {
                                            pool.add(stream);
                                            LOGGER.infof("[OTS] Created: slugName=%s", stream.getSlugName());
                                        })
                                        .replaceWith(stream);
                            });
                });
    }

    public Uni<OneTimeStream> start(String otsSlugName) {
        return pool.get(otsSlugName)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(new RuntimeException("OTS stream not found: " + otsSlugName));
                    }
                    stream.setStatus(StreamStatus.WARMING_UP);
                    LOGGER.infof("[OTS] Scheduling emission for '%s'", otsSlugName);
                    otsStreamScheduler.scheduleStream(stream);
                    return Uni.createFrom().item(stream);
                });
    }

    public Uni<OneTimeStream> getBySlugName(String slugName) {
        return pool.get(slugName);
    }

    public Uni<OneTimeStream> getById(String streamId) {
        return repository.findById(streamId);
    }

    public Uni<Void> delete(String streamId) {
        return repository.findById(streamId)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(new RuntimeException("Stream not found: " + streamId));
                    }
                    return pool.stopAndRemove(stream.getSlugName())
                            .chain(ignored -> repository.delete(streamId));
                });
    }
}
