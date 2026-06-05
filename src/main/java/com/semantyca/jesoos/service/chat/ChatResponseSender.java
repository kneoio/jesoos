package com.semantyca.jesoos.service.chat;

import com.semantyca.core.model.cnst.MessageType;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.model.chat.ChatMessageEnvelope;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.Consumer;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class ChatResponseSender {
    private static final Logger LOGGER = Logger.getLogger(ChatResponseSender.class);

    @Inject
    ChatRepository chatRepository;

    public Uni<Void> send(String text, String djName, String connectionId,
                          long userId, String slugName, ChatType chatType,
                          Consumer<String> chunkHandler, Consumer<String> completionHandler) {
        return Uni.createFrom().voidItem()
                .invoke(() -> {
                    chunkHandler.accept(ChatMessageDTO.chunk(text, djName, connectionId).build().toJson());
                    chunkHandler.accept(ChatMessageDTO.processingDone(connectionId).build().toJson());

                    chatRepository.appendToConversation(
                            ChatRepository.connectionKey(connectionId),
                            LlmMessage.text(LlmMessage.Role.ASSISTANT, text));

                    ChatMessageEnvelope botMessage = ChatMessageEnvelope.of(MessageType.BOT, djName, text, System.currentTimeMillis(), connectionId);
                    chatRepository.saveChatMessage(userId, slugName, chatType, botMessage)
                            .subscribe().with(success -> {}, err -> LOGGER.error("Failed to save bot message", err));

                    completionHandler.accept(ChatMessageDTO.bot(text, djName, connectionId)
                            .timestamp(System.currentTimeMillis()).build().toJson());
                })
                .runSubscriptionOn(getDefaultWorkerPool());
    }
}
