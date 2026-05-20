package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.RadioStream;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.PlaylistQueueService;
import com.semantyca.jesoos.service.agenda.AgendaService;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandPool extends AbstractStreamPool<ILiveStream> {
    private static final Logger LOGGER = Logger.getLogger(BrandPool.class);

    private final BrandService brandService;
    private final AgendaService agendaService;
    private final MetricPublisher metricPublisher;
    private final ScenePool scenePool;
    private final DjStateService djStateService;
    private final PlaylistQueueService playlistQueueService;

    @Inject
    public BrandPool(BrandService brandService, AgendaService agendaService, MetricPublisher metricPublisher, ScenePool scenePool, DjStateService djStateService, PlaylistQueueService playlistQueueService) {
        this.brandService = brandService;
        this.agendaService = agendaService;
        this.metricPublisher = metricPublisher;
        this.scenePool = scenePool;
        this.djStateService = djStateService;
        this.playlistQueueService = playlistQueueService;
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
                                return agendaService.getStreamAgenda(brand, SuperUser.build())
                                        .invoke(schedule -> {
                                            if (schedule.getTotalScenes() == 0) {
                                                throw new RuntimeException("Station '" + name + "' created with 0 scenes — agenda is empty");
                                            }
                                            newStream.setAgenda(schedule);
                                            pool.put(name, newStream);
                                            LOGGER.infof("BrandPool: Station '%s' created with %s scenes", newStream.getSlugName(), schedule.getTotalScenes());
                                        })
                                        .map(schedule -> (ILiveStream) newStream)
                                        .onFailure().invoke(e -> {
                                            LOGGER.errorf("Agenda build failed for brand '%s': %s", name, e.getMessage());
                                            metricPublisher.publishMetric(name, MetricEventType.ERROR, ProcessType.INDEPENDENT,
                                                    "agenda_empty_or_failed", Map.of("brand", name), null);
                                        });
                            });
                })
                .onItem().invoke(stream -> metricPublisher.publishMetric(brandName, MetricEventType.INFORMATION, ProcessType.INDEPENDENT, "agenda_created", Map.of("status", stream.getStatus().name())))
                .call(stream -> playlistQueueService.resetQueue(brandName)
                        .onFailure().invoke(err -> LOGGER.warnf("Failed to reset playlist queue for brand '%s': %s", brandName, err.getMessage()))
                        .onFailure().recoverWithNull())
                .onFailure().invoke(failure -> LOGGER.errorf("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Map<String, StreamAgenda> getAll() {
        return pool.entrySet().stream()
                .filter(entry -> entry.getValue().getAgenda() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getAgenda()));
    }

    public Collection<ILiveStream> getStationsSnapshot() {
        return getSnapshot();
    }

    @Override
    protected void onRemoved(String slugName, ILiveStream stream) {
        scenePool.removeActiveScene(slugName);
        djStateService.remove(slugName);
        metricPublisher.publishMetric(slugName, MetricEventType.INFORMATION, ProcessType.INDEPENDENT, "station_stop", Map.of("status", stream.getStatus().name()));
    }

    @Override
    protected Logger getLogger() {
        return LOGGER;
    }
}
