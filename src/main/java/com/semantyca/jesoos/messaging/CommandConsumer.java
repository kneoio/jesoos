package com.semantyca.jesoos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.jesoos.service.CommandService;
import com.semantyca.mixpla.dto.queue.command.CommandDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandConsumer {

    private static final Logger LOGGER = Logger.getLogger(CommandConsumer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Inject
    CommandService commandService;

    @Incoming("commands")
    public Uni<Void> consume(Message<byte[]> message) {
        byte[] payload = message.getPayload();

        return Uni.createFrom().item(() -> {
                    try {
                        return objectMapper.readValue(payload, CommandDTO.class);
                    } catch (Exception e) {
                        LOGGER.error("Failed to deserialize CommandDTO", e);
                        throw new RuntimeException(e);
                    }
                })
                .chain(dto -> {
                    LOGGER.debugf("Received command: type=%s command=%s", dto.type(), dto.command());
                    return commandService.handleQueueCommand(dto);
                })
                .onItem().transformToUni(v -> Uni.createFrom().completionStage(message.ack()))
                .onFailure().recoverWithUni(e -> {
                    LOGGER.errorf("Failed processing command message: %s", e.getMessage());
                    return Uni.createFrom().completionStage(message.nack(e));
                });
    }
}
