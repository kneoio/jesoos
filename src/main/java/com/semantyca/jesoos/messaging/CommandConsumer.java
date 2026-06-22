package com.semantyca.jesoos.messaging;

import com.semantyca.core.dto.queue.command.CommandDTO;
import com.semantyca.core.messaging.AbstractCommandConsumer;
import com.semantyca.jesoos.service.CommandService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

@ApplicationScoped
public class CommandConsumer extends AbstractCommandConsumer {

    @Inject
    CommandService commandService;

    @Incoming("commands")
    public Uni<Void> consume(Message<byte[]> message) {
        return processMessage(message);
    }

    @Override
    protected Uni<Void> handleCommand(CommandDTO dto) {
        return commandService.handleQueueCommand(dto);
    }
}
