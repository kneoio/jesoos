package com.semantyca.jesoos.service.chat.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public class OpenAiChatLlmClient implements ChatLlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenAIClient client;

    public OpenAiChatLlmClient(String apiKey) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    @Override
    public CompletionStage<LlmResponse> createMessage(LlmRequest request) {
        return client.async().chat().completions().create(toParams(request))
                .thenApply(OpenAiChatLlmClient::toResponse);
    }

    @Override
    public CompletionStage<String> streamText(LlmRequest request, Consumer<String> chunkConsumer) {
        StringBuilder fullResponse = new StringBuilder();

        return client.async().chat().completions().createStreaming(toParams(request))
                .subscribe(new AsyncStreamResponse.Handler<>() {
                    @Override
                    public void onNext(ChatCompletionChunk chunk) {
                        try {
                            if (!chunk.choices().isEmpty()) {
                                chunk.choices().get(0).delta().content().ifPresent(text -> {
                                    fullResponse.append(text);
                                    chunkConsumer.accept(text);
                                });
                            }
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onComplete(@NotNull Optional<Throwable> error) {}
                })
                .onCompleteFuture()
                .thenApply(ignored -> fullResponse.toString());
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    private static ChatCompletionCreateParams toParams(LlmRequest request) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens());

        if (request.system() != null && !request.system().isBlank()) {
            builder.addMessage(ChatCompletionSystemMessageParam.builder()
                    .content(request.system())
                    .build());
        }

        for (LlmMessage msg : request.messages()) {
            builder.addMessage(toMessageParam(msg));
        }

        for (LlmTool tool : request.tools()) {
            builder.addTool(toTool(tool));
        }

        return builder.build();
    }

    private static ChatCompletionMessageParam toMessageParam(LlmMessage msg) {
        return switch (msg.kind()) {
            case TEXT -> msg.role() == LlmMessage.Role.USER
                    ? ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                            .content(ChatCompletionUserMessageParam.Content.ofText(msg.text()))
                            .build())
                    : ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder()
                            .content(msg.text())
                            .build());
            case TOOL_USE -> {
                LlmToolCall tc = msg.toolCall();
                String argsJson;
                try {
                    argsJson = MAPPER.writeValueAsString(tc.input());
                } catch (Exception e) {
                    argsJson = "{}";
                }
                yield ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder()
                        .addToolCall(ChatCompletionMessageToolCall.builder()
                                .id(tc.id())
                                .function(ChatCompletionMessageToolCall.Function.builder()
                                        .name(tc.name())
                                        .arguments(argsJson)
                                        .build())
                                .build())
                        .build());
            }
            case TOOL_RESULT -> ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                    .toolCallId(msg.toolResultId())
                    .content(msg.toolResultContent())
                    .build());
        };
    }

    private static ChatCompletionTool toTool(LlmTool llmTool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", llmTool.properties());
        if (!llmTool.required().isEmpty()) {
            schema.put("required", llmTool.required());
        }
        return ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name(llmTool.name())
                        .description(llmTool.description())
                        .parameters(JsonValue.from(schema))
                        .build())
                .build();
    }

    private static LlmResponse toResponse(ChatCompletion completion) {
        if (completion.choices().isEmpty()) {
            return new LlmResponse("", null);
        }
        ChatCompletionMessage message = completion.choices().get(0).message();
        String text = message.content().orElse("");

        LlmToolCall toolCall = message.toolCalls()
                .flatMap(calls -> calls.isEmpty() ? Optional.empty() : Optional.of(calls.get(0)))
                .map(tc -> {
                    Map<String, Object> input;
                    try {
                        input = MAPPER.readValue(tc.function().arguments(),
                                new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        input = Map.of();
                    }
                    return new LlmToolCall(tc.id(), tc.function().name(), input);
                })
                .orElse(null);

        return new LlmResponse(text, toolCall);
    }
}
