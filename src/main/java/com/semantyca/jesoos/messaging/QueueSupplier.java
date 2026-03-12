package com.semantyca.jesoos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.mixpla.dto.queue.SongQueueMessageDTO;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class QueueSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueSupplier.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    @Channel("queue-requests")
    Emitter<byte[]> songEmitter;

    public Uni<Void> sendSongsToQueue(String brandSlug, SongQueueMessageDTO message) {
        message.setBrandSlug(brandSlug);
        message.setMessageId(UUID.randomUUID());

        return Uni.createFrom().item(() -> {
                    try {
                        byte[] bytes = objectMapper.writeValueAsBytes(message);
                        OutgoingRabbitMQMetadata metadata = new OutgoingRabbitMQMetadata.Builder()
                                .withRoutingKey(brandSlug)
                                .build();
                        Message<byte[]> msg = Message.of(bytes).addMetadata(metadata);
                        songEmitter.send(msg);
                        LOGGER.info("Sent to queue, brand: {},  messageId: {}", brandSlug,  message.getMessageId());
                        return null;
                    } catch (Exception e) {
                        LOGGER.error("Failed to send - brand: {}, messageId: {}",
                                brandSlug, message.getMessageId(), e);
                        throw new RuntimeException("Failed to send message", e);
                    }
                });
    }
}