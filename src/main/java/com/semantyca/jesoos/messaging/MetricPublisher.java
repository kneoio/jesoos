package com.semantyca.jesoos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.jesoos.EnvConst;
import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class MetricPublisher {


    private static final Logger LOGGER = Logger.getLogger(MetricPublisher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Inject
    @Channel("metrics")
    Emitter<byte[]> metricsEmitter;

    public void publishMetric(String brandName, MetricEventType eventType,  Map<String, Object> payload) {
        try {
            MetricEventDTO event = MetricEventDTO.of(
                    EnvConst.APP_ID,
                    brandName,
                    eventType,
                    UUID.randomUUID(),
                    payload
            );
            publish(event)
                    .subscribe()
                    .with(
                            v -> LOGGER.debugf("Published metric for {}: {}", brandName, eventType),
                            e -> LOGGER.errorf("Failed to publish metric for {}: {}", brandName, e.getMessage())
                    );
        } catch (Exception e) {
            LOGGER.errorf("Error publishing metric for {}: {}", brandName, e.getMessage());
        }
    }

    public Uni<Void> publish(MetricEventDTO event) {
        return Uni.createFrom().item(() -> {
                    try {
                        return objectMapper.writeValueAsBytes(event);
                    } catch (Exception e) {
                        LOGGER.error("Failed to serialize metric event", e);
                        throw new RuntimeException(e);
                    }
                })
                .invoke(bytes -> metricsEmitter.send(bytes))
                .onFailure().invoke(e -> LOGGER.error("Failed to publish metric event", e))
                .onItem().ignore().andContinueWithNull();
    }
}