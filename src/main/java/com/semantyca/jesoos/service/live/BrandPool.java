package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.RadioStream;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.agenda.AgendaService;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandPool {
    private static final Logger LOGGER = Logger.getLogger(BrandPool.class);
    private final ConcurrentHashMap<String, ILiveStream> pool = new ConcurrentHashMap<>();
    private final BrandService brandService;
    private final AgendaService agendaService;
    private final MetricPublisher metricPublisher;
    private final ScenePool scenePool;
    private final DjStateService djStateService;

    @Inject
    public BrandPool(BrandService brandService, AgendaService agendaService, MetricPublisher metricPublisher, ScenePool scenePool, DjStateService djStateService) {
        this.brandService = brandService;
        this.agendaService = agendaService;
        this.metricPublisher = metricPublisher;
        this.scenePool = scenePool;
        this.djStateService = djStateService;
    }

    public Uni<ILiveStream> getRadioStream(String brandName) {
        LOGGER.infof("Attempting to get (or start) brand: %s", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(name -> {
                    ILiveStream existing = pool.get(name);
                    if (existing != null) {
                        LOGGER.infof("Stream {} already exists in pool (status: %s). Ignoring duplicate start request.",
                                name, existing.getStatus());
                        return Uni.createFrom().item(existing);
                    }

                    return brandService.getBySlugName(name)
                            .onItem().transformToUni(brand -> {
                                if (brand == null) {
                                    LOGGER.warnf("Brand with brandName %s not found. Cannot get.", name);
                                    pool.remove(name);
                                    return Uni.createFrom().failure(new RuntimeException("Station not found: " + name));
                                }

                                RadioStream newStream = new RadioStream(brand);
                                newStream.setStatus(StreamStatus.WARMING_UP);
                                pool.put(name, newStream);

                                return agendaService.getStreamAgenda(brand, SuperUser.build())
                                        .invoke(schedule -> {
                                            newStream.setAgenda(schedule);
                                            LOGGER.infof("BrandPool: Station '%s' created with %s scenes", newStream.getSlugName(), schedule.getTotalScenes());
                                        })
                                        .map(schedule -> (ILiveStream) newStream);
                            });
                })
                .onItem().invoke(agenda -> metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.INDEPENDENT, "agenda_created", Map.of("status", agenda.getStatus().name())))
                .onFailure().invoke(failure -> LOGGER.errorf("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Uni<ILiveStream> get(String brandName) {
        ILiveStream stream = pool.get(brandName);
        return Uni.createFrom().item(stream);
    }

    public Collection<ILiveStream> getStationsSnapshot() {
        return new ArrayList<>(pool.values());
    }

    public Map<String, StreamAgenda> getAll() {
        return pool.entrySet().stream()
                .filter(entry -> entry.getValue().getAgenda() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getAgenda()));
    }

    public Uni<ILiveStream> stopAndRemove(String brandName) {
        LOGGER.infof("Attempting to stop and remove station: %s", brandName);
        ILiveStream liveAgenda = pool.remove(brandName);

        if (liveAgenda != null) {
            scenePool.removeActiveScene(brandName);
            djStateService.remove(brandName);
            liveAgenda.setStatus(StreamStatus.OFF_LINE);
            metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.INDEPENDENT, "station_stop", Map.of("status", liveAgenda.getStatus().name()));
            return Uni.createFrom().item(liveAgenda);
        } else {
            LOGGER.warnf("Station {} not found in pool during stopAndRemove.", brandName);
            return Uni.createFrom().nullItem();
        }
    }

    @PreDestroy
    void cleanup() {
        LOGGER.info("BrandPool cleanup: stopping all brands and clearing resources");
        pool.keySet().forEach(brandName -> {
            scenePool.removeActiveScene(brandName);
            djStateService.remove(brandName);
        });
        pool.clear();
    }
}
