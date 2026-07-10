package com.semantyca.jesoos.service;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.model.stream.OneTimeStream;
import com.semantyca.jesoos.model.stream.SongSourceScope;
import com.semantyca.jesoos.repository.OneTimeStreamRepository;
import com.semantyca.jesoos.repository.OtsDefinitionRepository;
import com.semantyca.jesoos.service.agenda.AgendaService;
import com.semantyca.jesoos.service.live.OneTimeStreamPool;
import com.semantyca.jesoos.service.live.OtsStreamScheduler;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.stream.OtsDefinition;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OneTimeStreamService {
    private static final Logger LOGGER = Logger.getLogger(OneTimeStreamService.class);

    private final BrandService brandService;
    private final ScriptService scriptService;
    private final OneTimeStreamRepository repository;
    private final OtsDefinitionRepository otsDefinitionRepository;
    private final AgendaService agendaService;
    private final UserService userService;
    private final OneTimeStreamPool pool;
    private final OtsStreamScheduler otsStreamScheduler;

    @Inject
    public OneTimeStreamService(BrandService brandService, ScriptService scriptService,
                                OneTimeStreamRepository repository,
                                OtsDefinitionRepository otsDefinitionRepository,
                                AgendaService agendaService,
                                UserService userService,
                                OneTimeStreamPool pool, OtsStreamScheduler otsStreamScheduler) {
        this.brandService = brandService;
        this.scriptService = scriptService;
        this.repository = repository;
        this.otsDefinitionRepository = otsDefinitionRepository;
        this.agendaService = agendaService;
        this.userService = userService;
        this.pool = pool;
        this.otsStreamScheduler = otsStreamScheduler;
    }

    public Uni<OneTimeStream> run(String brandSlugName, UUID scriptId, UUID agentId, Map<String, Object> userVariables, IUser user) {
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
                                OneTimeStream stream = new OneTimeStream(brand, script, vars, agentId);
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

    public Uni<OneTimeStream> startFromDefinition(String slugName) {
        IUser actingUser = SuperUser.build();
        return otsDefinitionRepository.findBySlugName(slugName)
                .chain(definition -> {
                    if (definition == null) {
                        return Uni.createFrom().failure(new RuntimeException("OTS definition not found: " + slugName));
                    }
                    return scriptService.getById(definition.getScriptId(), actingUser)
                            .chain(script -> {
                                if (script == null) {
                                    return Uni.createFrom().failure(new RuntimeException("Script not found: " + definition.getScriptId()));
                                }

                                Uni<Brand> brandUni = definition.getBrandId() != null
                                        ? brandService.getById(definition.getBrandId())
                                        : Uni.createFrom().nullItem();
                                Uni<IUser> ownerUni = definition.getBrandId() == null
                                        ? userService.get(definition.getAuthor()).map(opt -> opt.orElse(null))
                                        : Uni.createFrom().nullItem();

                                return Uni.combine().all().unis(brandUni, ownerUni).asTuple()
                                        .chain(tuple -> {
                                            Brand brand = tuple.getItem1();
                                            IUser owner = tuple.getItem2();

                                            if (definition.getBrandId() != null && brand == null) {
                                                return Uni.createFrom().failure(new RuntimeException("Brand not found: " + definition.getBrandId()));
                                            }
                                            if (brand == null && definition.getAgentId() == null) {
                                                return Uni.createFrom().failure(new RuntimeException("OTS definition missing agentId for owner scope: " + slugName));
                                            }

                                            ZoneId fallbackZone = (owner != null && owner.getTimeZone() != null)
                                                    ? owner.getTimeZone().toZoneId()
                                                    : ZoneId.systemDefault();

                                            OneTimeStream stream = new OneTimeStream(definition, script, brand, fallbackZone);
                                            stream.setStatus(StreamStatus.PENDING);

                                            SongSourceScope scope = brand != null
                                                    ? new SongSourceScope.BrandScope(brand.getId())
                                                    : new SongSourceScope.OwnerScope(definition.getAuthor());

                                            return agendaService.buildOtsAgenda(scope, stream.getAiAgentId(), stream.getTimeZone(),
                                                            definition.getScriptId(), LocalDateTime.now(stream.getTimeZone()), actingUser)
                                                    .invoke(agenda -> {
                                                        stream.setAgenda(agenda);
                                                        pool.add(stream);
                                                        stream.setStatus(StreamStatus.WARMING_UP);
                                                        otsStreamScheduler.scheduleStream(stream);
                                                        LOGGER.infof("[OTS] Started from definition: slugName=%s", stream.getSlugName());
                                                    })
                                                    .replaceWith(stream);
                                        });
                            });
                });
    }

    public Uni<OneTimeStream> start(String otsSlugName) {
        return startFromDefinition(otsSlugName);
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
