package com.semantyca.jesoos.service.chat.ad;

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
public class AdContinuationHandler {
    private static final Logger LOGGER = Logger.getLogger(AdContinuationHandler.class);

    @Inject
    AdGraph adGraph;
    @Inject
    AdSessionManager adSessionManager;
    @Inject
    ChatResponseSender responseSender;

    public Uni<Void> execute(String userMessage, String djName, IUser user,
                             String connectionId, String slugName,
                             Consumer<String> chunkHandler, Consumer<String> completionHandler) {
        return adGraph.processUserTurn(connectionId, userMessage)
                .flatMap(result -> {
                    String responseText = result.action() == AdResult.Action.AD_CREATED
                            ? "Your advertisement has been recorded and will air on the station!"
                            : result.question();
                    return responseSender.send(responseText, djName, connectionId, user.getId(), slugName, ChatType.PUBLIC, chunkHandler, completionHandler);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.errorf("[AD] processUserTurn failed connectionId=%s: %s", connectionId, err.getMessage());
                    adSessionManager.end(connectionId);
                    chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());
                    completionHandler.accept(ChatMessageDTO.error("Ad creation failed, please try again.", "system", connectionId).build().toJson());
                    return Uni.createFrom().voidItem();
                });
    }
}
