package com.semantyca.jesoos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.jesoos.util.TimeContextUtil;
import com.semantyca.jesoos.util.TimeFormatUtil;
import com.semantyca.mixpla.dto.queue.livestream.IntroInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class QueueSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueSupplier.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    private MetricPublisher metricPublisher;

    @Inject
    @Channel("streaming")
    Emitter<byte[]> songEmitter;

    public Uni<Void> sendSongsToQueue(String brandSlug, SongQueueMessageDTO message, UUID traceId) {
        UUID emissionTraceId = UUID.randomUUID();
        message.setBrandSlug(brandSlug);
        message.setMessageId(UUID.randomUUID());
        message.setTraceId(emissionTraceId);
        long now = System.currentTimeMillis();
        message.setTimestamp(now);

        return Uni.createFrom().item(() -> {
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(message);
                OutgoingRabbitMQMetadata metadata = new OutgoingRabbitMQMetadata.Builder()
                        .withRoutingKey(brandSlug)
                        .build();
                Message<byte[]> msg = Message.of(bytes).addMetadata(metadata);
                songEmitter.send(msg);
                metricPublisher.publishMetric(brandSlug, MetricEventType.INFORMATION, ProcessType.FLOW, "entry_emitted",
                        Map.of(
                                "message", message,
                                "supplied", TimeFormatUtil.formatEpochMillis(now)
                        ),
                        emissionTraceId);
                int totalDuration = 0;
                if (message.getSongs() != null) {
                    totalDuration += message.getSongs().values().stream()
                            .mapToInt(SongInfoDTO::getDurationSeconds).sum();
                }
                if (message.getFilePaths() != null) {
                    totalDuration += message.getFilePaths().values().stream()
                            .mapToInt(IntroInfoDTO::getDurationSeconds).sum();
                }
                metricPublisher.trackEmission(brandSlug, totalDuration);
                return null;
            } catch (Exception e) {
                LOGGER.error("Failed to send - brand: {}, messageId: {}, traceId: {}", brandSlug, message.getMessageId(), traceId, e);
                throw new RuntimeException("Failed to send message", e);
            }
        });
    }
}