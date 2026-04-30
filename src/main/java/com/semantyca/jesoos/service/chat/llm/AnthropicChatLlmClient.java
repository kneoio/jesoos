package com.semantyca.jesoos.service.chat.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public class AnthropicChatLlmClient {
    private final AnthropicClient client;

    public AnthropicChatLlmClient(String apiKey) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }


    public CompletionStage<Message> createMessage(MessageCreateParams params) {
        return client.async().messages().create(params);
    }


    public CompletionStage<String> streamText(MessageCreateParams params, Consumer<String> chunkConsumer) {
        StringBuilder fullResponse = new StringBuilder();
        boolean[] inThinking = {false};

        return client.async().messages().createStreaming(params)
                .subscribe(new AsyncStreamResponse.Handler<>() {
                    @Override
                    public void onNext(RawMessageStreamEvent chunk) {
                        try {
                            if (chunk.contentBlockDelta().isPresent()) {
                                RawContentBlockDelta delta = chunk.contentBlockDelta().get().delta();
                                if (delta.text().isPresent()) {
                                    String text = delta.text().get().text();
                                    fullResponse.append(text);

                                    if (text.contains("<thinking>")) {
                                        inThinking[0] = true;
                                    }
                                    if (text.contains("</thinking>")) {
                                        inThinking[0] = false;
                                    }

                                    if (!inThinking[0]
                                            && !text.contains("<thinking>")
                                            && !text.contains("</thinking>")) {
                                        chunkConsumer.accept(text);
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onComplete(@NotNull Optional<Throwable> error) {
                    }
                })
                .onCompleteFuture()
                .thenApply(ignored -> fullResponse.toString());
    }
}
