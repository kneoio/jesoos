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
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
        
        Uni.createFrom().item(() -> {
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            
            // Get all active brands from the pool
            Map<String, ILiveStream> activeStreams = brandPool.getStationsSnapshot().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ILiveStream::getSlugName,
                            stream -> stream
                    ));
            
            if (activeStreams.isEmpty()) {
                LOGGER.info("No active brands found for agenda rebuild");
                return Map.of(
                    "totalBrands", 0,
                    "successCount", 0,
                    "failureCount", 0,
                    "message", "No active brands to rebuild"
                );
            }
            
            LOGGER.infof("Found %d active brands for agenda rebuild", activeStreams.size());
            
            // Rebuild agenda for each active brand
            activeStreams.forEach((brandSlug, stream) -> {
                try {
                    rebuildBrandAgenda(brandSlug, stream);
                    successCount.incrementAndGet();
                    LOGGER.infof("Successfully rebuilt agenda for brand: %s", brandSlug);
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    LOGGER.errorf(e, "Failed to rebuild agenda for brand: %s", brandSlug);
                }
            });
            
            return Map.of(
                "totalBrands", activeStreams.size(),
                "successCount", successCount.get(),
                "failureCount", failureCount.get(),
                "message", String.format("Rebuild completed: %d success, %d failures", 
                        successCount.get(), failureCount.get())
            );
        })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
        .subscribe().with(
            result -> {
                LOGGER.infof("Daily agenda rebuild completed: %s", result.get("message"));
                metricPublisher.publishMetric("system", MetricEventType.INFORMATION, ProcessType.CRON,
                        "daily_agenda_rebuild_completed", result, null);
            },
            failure -> {
                LOGGER.error("Daily agenda rebuild failed", failure);
                metricPublisher.publishMetric("system", MetricEventType.ERROR, ProcessType.CRON,
                        "daily_agenda_rebuild_failed", Map.of("error", failure.getMessage()), null);
            }
        );
    }

    private void rebuildBrandAgenda(String brandSlug, ILiveStream existingStream) {
        try {
            // Get fresh brand data
            brandService.getBySlugName(brandSlug)
                    .onItem().transformToUni(brand -> {
                        if (brand == null) {
                            LOGGER.warnf("Brand %s not found, removing from pool", brandSlug);
                            brandPool.stopAndRemove(brandSlug);
                            return Uni.createFrom().failure(new RuntimeException("Brand not found"));
                        }
                        
                        // Build new agenda
                        return agendaService.getStreamAgenda(brand, SuperUser.build())
                                .invoke(newAgenda -> {
                                    // Update the existing stream with new agenda
                                    existingStream.setAgenda(newAgenda);
                                    LOGGER.infof("Rebuilt agenda for brand %s with %d scenes", 
                                            brandSlug, newAgenda.getTotalScenes());
                                    
                                    // Publish metric for successful rebuild
                                    metricPublisher.publishMetric(brandSlug, MetricEventType.INFORMATION, 
                                            ProcessType.CRON, "agenda_rebuilt", 
                                            Map.of(
                                                "totalScenes", newAgenda.getTotalScenes(),
                                                "rebuildTime", LocalDateTime.now().toString()
                                            ), null);
                                });
                    })
                    .await().indefinitely();
                    
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to rebuild agenda for brand: %s", brandSlug);
            throw e;
        }
    }

    public Uni<Map<String, Object>> rebuildSingleBrandAgenda(String brandSlug) {
        LOGGER.infof("Manual agenda rebuild requested for brand: %s", brandSlug);
        
        return Uni.createFrom().item(() -> {
            ILiveStream existingStream = brandPool.getStationsSnapshot().stream()
                    .filter(stream -> stream.getSlugName().equals(brandSlug))
                    .findFirst()
                    .orElse(null);
                    
            if (existingStream == null) {
                throw new IllegalArgumentException("Brand not found in active pool: " + brandSlug);
            }
            
            try {
                rebuildBrandAgenda(brandSlug, existingStream);
                return Map.of(
                    "success", true,
                    "brand", brandSlug,
                    "message", "Agenda rebuilt successfully",
                    "timestamp", LocalDateTime.now().toString()
                );
            } catch (Exception e) {
                return Map.of(
                    "success", false,
                    "brand", brandSlug,
                    "error", e.getMessage(),
                    "timestamp", LocalDateTime.now().toString()
                );
            }
        })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
