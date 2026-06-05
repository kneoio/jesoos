package com.semantyca.jesoos.service.chat.ots;

import com.semantyca.core.model.user.IUser;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.service.chat.ChatResponseSender;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.Consumer;

@ApplicationScoped
public class OtsContinuationHandler {
    private static final Logger LOGGER = Logger.getLogger(OtsContinuationHandler.class);

    @Inject
    OtsGraph otsGraph;
    @Inject
    OtsSessionManager otsSessionManager;
    @Inject
    ChatResponseSender responseSender;

    public Uni<Void> execute(String userMessage, String djName, IUser user,
                             String connectionId, String slugName,
                             Consumer<String> chunkHandler, Consumer<String> completionHandler) {
        return otsGraph.processUserTurn(connectionId, userMessage)
                .flatMap(result -> {
                    String responseText = result.action() == OtsResult.Action.STREAM_STARTED
                            ? "Your stream is live! Tune in here: " + result.mixplaUrl()
                            : result.question();
                    return responseSender.send(responseText, djName, connectionId, user.getId(), slugName, ChatType.PUBLIC, chunkHandler, completionHandler);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.errorf("[OTS] processUserTurn failed connectionId=%s: %s", connectionId, err.getMessage());
                    otsSessionManager.end(connectionId);
                    chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                    completionHandler.accept(ChatMessageDTO.error("Stream setup failed, please try again.", "system", connectionId).build().toJson());
                    return Uni.createFrom().voidItem();
                });
    }
}
