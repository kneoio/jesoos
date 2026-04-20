package com.semantyca.jesoos.service.maintenance;

import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.brand.Brand;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FeaturedStationManager {
    private static final Logger LOGGER = Logger.getLogger(FeaturedStationManager.class);
    private static final UUID FEATURED_LABEL_ID = UUID.fromString("8ec93423-dd7c-4ab7-9fa5-9fd2d34df2ee");
    private static final int MAX_RETRIES = 10;
    private static final Duration RETRY_INITIAL_DELAY = Duration.ofSeconds(5);
    private static final Duration RETRY_MAX_DELAY = Duration.ofMinutes(2);

    @Inject
    BrandService brandService;

    @Inject
    BrandPool brandPool;

    @Inject
    MetricPublisher metricPublisher;

    private final Set<String> featuredStations = ConcurrentHashMap.newKeySet();
    private UUID traceId;

    void onStart(@Observes StartupEvent event) {
        this.traceId = UUID.randomUUID();
        LOGGER.infof("[FEATURED] Starting featured station manager with traceId: %s", traceId);
        initializeFeaturedStations();
    }

    private void initializeFeaturedStations() {
        LOGGER.infof("[FEATURED] Initializing featured stations with label ID: %s", FEATURED_LABEL_ID);

        brandService.getByLabelIds(List.of(FEATURED_LABEL_ID))
                .onItem().transformToMulti(brands -> Multi.createFrom().iterable(brands))
                .onItem().transformToUniAndConcatenate(brand ->
                        initializeFeaturedStation(brand)
                                .onFailure().retry()
                                .withBackOff(RETRY_INITIAL_DELAY, RETRY_MAX_DELAY)
                                .atMost(MAX_RETRIES)
                                .onFailure().invoke(failure -> {
                                    String slugName = brand.getSlugName();
                                    LOGGER.errorf(failure, "[FEATURED] FATAL: Failed to initialize featured station %s after %d retries", slugName, MAX_RETRIES);
                                    metricPublisher.publishMetric(slugName, MetricEventType.FATAL_ERROR,
                                            ProcessType.INDEPENDENT, "featured_station_fatal_failure",
                                            Map.of("brand", slugName, "maxRetries", MAX_RETRIES,
                                                    "error", failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName(),
                                                    "checkerTraceId", traceId.toString()), traceId);
                                })
                                .onFailure().recoverWithNull()
                )
                .collect().asList()
                .subscribe().with(
                        results -> LOGGER.infof("[FEATURED] Featured stations initialization completed. Total: %d", results.size()),
                        failure -> LOGGER.errorf(failure, "[FEATURED] Failed to initialize featured stations")
                );
    }

    private Uni<Void> initializeFeaturedStation(Brand brand) {
        String slugName = brand.getSlugName();
        LOGGER.infof("[FEATURED] Initializing featured station: %s", slugName);

        return brandPool.getRadioStream(slugName)
                .invoke(stream -> {
                    LOGGER.infof("[FEATURED] Station %s initialized successfully", slugName);
                    featuredStations.add(slugName);

                    metricPublisher.publishMetric(slugName, MetricEventType.IMPORTANT_INFORMATION,
                            ProcessType.INDEPENDENT, "featured_station_initialized",
                            Map.of("brand", slugName, "labelId", FEATURED_LABEL_ID.toString(),
                                    "status", "running", "checkerTraceId", traceId.toString()), traceId);
                })
                .onFailure().invoke(failure -> {
                    LOGGER.warnf(failure, "[FEATURED] Failed to initialize featured station: %s - will retry", slugName);
                    metricPublisher.publishMetric(slugName, MetricEventType.WARNING,
                            ProcessType.INDEPENDENT, "featured_station_init_retry",
                            Map.of("brand", slugName, "error", failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName(),
                                    "checkerTraceId", traceId.toString()), traceId);
                })
                .replaceWithVoid();
    }

    public boolean isFeaturedStation(String slug) {
        return featuredStations.contains(slug);
    }

    public Set<String> getFeaturedStations() {
        return Set.copyOf(featuredStations);
    }
}
