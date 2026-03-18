package com.semantyca.jesoos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MetricPublisher {


    private static final Logger LOGGER = Logger.getLogger(MetricPublisher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Inject
    @Channel("metrics")
    Emitter<byte[]> metricsEmitter;

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