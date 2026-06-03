package com.semantyca.jesoos.service.maintenance;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.agenda.AgendaService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DailyAgendaRebuildService {
    private static final Logger LOGGER = Logger.getLogger(DailyAgendaRebuildService.class);
    
    private final BrandPool brandPool;
    private final AgendaService agendaService;
    private final BrandService brandService;
    private final MetricPublisher metricPublisher;

    @Inject
    public DailyAgendaRebuildService(BrandPool brandPool, AgendaService agendaService,
                                     BrandService brandService, MetricPublisher metricPublisher) {
        this.brandPool = brandPool;
        this.agendaService = agendaService;
        this.brandService = brandService;
        this.metricPublisher = metricPublisher;
    }

    @Scheduled(cron = "0 0 5 * * ?") // 5:00 AM daily
    public void rebuildAllAgendas() {
        LOGGER.info("Starting daily agenda rebuild for all active brands");

        List<ILiveStream> activeStreams = List.copyOf(brandPool.getStationsSnapshot());

        if (activeStreams.isEmpty()) {
            LOGGER.info("No active brands found for agenda rebuild");
            return;
        }

        LOGGER.infof("Found %d active brands for agenda rebuild", activeStreams.size());

        Multi.createFrom().iterable(activeStreams)
                .onItem().transformToUniAndMerge(stream -> rebuildBrandAgenda(stream.getSlugName(), stream)
                        .onFailure().recoverWithItem(e -> {
                            LOGGER.errorf(e, "Failed to rebuild agenda for brand: %s", stream.getSlugName());
                            return 0;
                        }))
                .collect().asList()
                .invoke(results -> {
                    long success = results.stream().filter(r -> r > 0).count();
                    long failure = results.stream().filter(r -> r == 0).count();
                    LOGGER.infof("Agenda rebuild completed: %d success, %d failures", success, failure);
                    metricPublisher.publishMetric("system", MetricEventType.INFORMATION, ProcessType.CRON,
                            "daily_agenda_rebuild_completed",
                            Map.of("totalBrands", activeStreams.size(), "successCount", success, "failureCount", failure),
                            null);
                })
                .subscribe().with(
                        ignored -> {},
                        failure -> {
                            LOGGER.error("Daily agenda rebuild pipeline failed", failure);
                            metricPublisher.publishMetric("system", MetricEventType.ERROR, ProcessType.CRON,
                                    "daily_agenda_rebuild_failed",
                                    Map.<String, Object>of("error", failure.getMessage()), null);
                        }
                );
    }

    private Uni<Integer> rebuildBrandAgenda(String brandSlug, ILiveStream existingStream) {
        return brandService.getBySlugName(brandSlug)
                .onItem().transformToUni(brand -> {
                    if (brand == null) {
                        LOGGER.warnf("Brand %s not found, removing from pool", brandSlug);
                        return brandPool.stopAndRemove(brandSlug)
                                .map(ignored -> 0);
                    }
                    return agendaService.getStreamAgenda(brand, SuperUser.build())
                            .invoke(newAgenda -> {
                                existingStream.setAgenda(newAgenda);
                                LOGGER.infof("Rebuilt agenda for brand %s with %d scenes", brandSlug, newAgenda.getTotalScenes());
                                metricPublisher.publishMetric(brandSlug, MetricEventType.INFORMATION,
                                        ProcessType.CRON, "agenda_rebuilt",
                                        Map.of("totalScenes", newAgenda.getTotalScenes(),
                                                "rebuildTime", LocalDateTime.now().toString()), null);
                            })
                            .map(ignored -> 1);
                });
    }

}
