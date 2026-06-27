package com.semantyca.jesoos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.jesoos.EnvConst;
import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MetricPublisher {
    private static final Logger LOGGER = Logger.getLogger(MetricPublisher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private static final int SILENCE_GRACE_SECONDS = 120;

    private final ConcurrentHashMap<String, Instant> nextExpectedEmitAt = new ConcurrentHashMap<>();

    @Inject
    @Channel("metrics")
    Emitter<byte[]> metricsEmitter;

    public void publishMetric(String brandName, MetricEventType eventType, ProcessType processType, String code, Map<String, Object> payload) {
        publishMetric(brandName, eventType, processType, code, payload, UUID.randomUUID());
    }

    public void publishMetric(String brandName, MetricEventType eventType, ProcessType processType, String code, Map<String, Object> payload, UUID traceId) {
        try {
            MetricEventDTO event = MetricEventDTO.of(
                    EnvConst.APP_ID,
                    brandName,
                    eventType,
                    processType,
                    traceId,
                    code,
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

    public void trackEmission(String brandName, int entryDurationSeconds) {
        nextExpectedEmitAt.put(brandName, Instant.now().plusSeconds(entryDurationSeconds));
    }

    /** Seconds elapsed past the moment the last emitted content was expected to finish.
     *  Returns Long.MIN_VALUE when the brand has no emission tracked yet. */
    public long secondsSinceExpectedEmit(String brandName) {
        Instant expected = nextExpectedEmitAt.get(brandName);
        if (expected == null) return Long.MIN_VALUE;
        return Instant.now().getEpochSecond() - expected.getEpochSecond();
    }

    @Scheduled(every = "60s", delay = 120, delayUnit = TimeUnit.SECONDS)
    void checkSilenceRisk() {
        Instant now = Instant.now();
        nextExpectedEmitAt.forEach((brand, expectedAt) -> {
            long secondsOverdue = now.getEpochSecond() - expectedAt.getEpochSecond() - SILENCE_GRACE_SECONDS;
            if (secondsOverdue > 60) {
                LOGGER.warnf("Silence risk for brand '%s': %ds overdue", brand, secondsOverdue);
                publishMetric(brand, MetricEventType.WARNING, ProcessType.CRON, "silence_risk", Map.of("secondsOverdue", secondsOverdue));
            }
        });
    }

    private Uni<Void> publish(MetricEventDTO event) {
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