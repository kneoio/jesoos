package com.semantyca.jesoos.service.maintenance;

import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.messaging.CommandPublisher;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.repository.brand.BrandRepository;
import com.semantyca.mixpla.dto.queue.command.CommandType;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AivoxNotifier {

    private static final Logger LOGGER = Logger.getLogger(AivoxNotifier.class);

    @Inject
    JesoosConfig jesoosConfig;

    @Inject
    BrandRepository brandRepository;

    @Inject
    CommandPublisher commandPublisher;

    @Inject
    MetricPublisher metricPublisher;

    void onStart(@Observes StartupEvent event) {
        if (!jesoosConfig.autostartEnabled()) {
            LOGGER.info("[BOOTSTRAPPER] Auto-start is disabled (aivox.autostart.enabled=false), skipping");
            return;
        }
        try {
            TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        brandRepository.getAutostartStations()
                .subscribe().with(
                        brands -> brands.forEach(brand -> {
                            UUID traceId = UUID.randomUUID();
                            commandPublisher.publishCommand(
                                    CommandType.AIVOX_INIT_BRAND,
                                    "init_brand",
                                    Map.of("brand", brand.getSlugName()),
                                    traceId
                            );
                            metricPublisher.publishMetric(
                                    brand.getSlugName(),
                                    MetricEventType.INFORMATION,
                                    ProcessType.FLOW,
                                    "aivox_init_brand_sent",
                                    Map.of("brand", brand.getSlugName()),
                                    traceId
                            );
                            LOGGER.infof("Sent AIVOX_INIT_BRAND for brand=%s", brand.getSlugName());
                        }),
                        err -> LOGGER.errorf("Failed to load brands for AIVOX_INIT_BRAND: %s", err.getMessage())
                );
    }
}
