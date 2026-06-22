package com.semantyca.jesoos.messaging;

import com.semantyca.core.dto.queue.command.CommandDTO;
import com.semantyca.core.messaging.AbstractCommandPublisher;
import com.semantyca.jesoos.EnvConst;
import com.semantyca.mixpla.dto.queue.command.CommandType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CommandPublisher extends AbstractCommandPublisher {

    private static final Logger LOGGER = Logger.getLogger(CommandPublisher.class);

    @Inject
    @Channel("commands-outgoing")
    Emitter<byte[]> emitter;

    @Override
    protected Emitter<byte[]> getEmitter() {
        return emitter;
    }

    public void publishCommand(CommandType type, String command, Map<String, Object> payload, UUID traceId) {
        LOGGER.infof("Publishing command type=%s command=%s traceId=%s", type, command, traceId);
        publishEvent(CommandDTO.of(EnvConst.APP_ID, type, traceId, command, payload));
    }
}
