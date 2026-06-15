package com.semantyca.jesoos.service.chat.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.*;
import org.jboss.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class AnthropicChatLlmClient implements ChatLlmClient {

    private static final Logger LOGGER = Logger.getLogger(AnthropicChatLlmClient.class);

    private final AnthropicClient client;

    public AnthropicChatLlmClient(String apiKey) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    @Override
    public CompletionStage<LlmResponse> createMessage(LlmRequest request) {
        return client.async().messages().create(toMessageCreateParams(request))
                .thenApply(AnthropicChatLlmClient::toResponse);
    }

    @Override
    public CompletionStage<String> streamText(LlmRequest request, Consumer<String> chunkConsumer) {
        StringBuilder fullResponse = new StringBuilder();
        boolean[] inThinking = {false};

        return client.async().messages().createStreaming(toMessageCreateParams(request))
                .subscribe(new AsyncStreamResponse.Handler<>() {
                    @Override
                    public void onNext(RawMessageStreamEvent chunk) {
                        try {
                            if (chunk.contentBlockDelta().isPresent()) {
                                RawContentBlockDelta delta = chunk.contentBlockDelta().get().delta();
                                if (delta.text().isPresent()) {
                                    String text = delta.text().get().text();
                                    fullResponse.append(text);
                                    if (text.contains("<thinking>")) inThinking[0] = true;
                                    if (text.contains("</thinking>")) inThinking[0] = false;
                                    if (!inThinking[0] && !text.contains("<thinking>") && !text.contains("</thinking>")) {
                                        chunkConsumer.accept(text);
                                    }
                                }
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

    private static MessageCreateParams toMessageCreateParams(LlmRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens());

        applySystem(builder, request);

        request.messages().forEach(msg -> builder.addMessage(toMessageParam(msg)));
        request.tools().forEach(tool -> builder.addTool(toTool(tool)));

        return builder.build();
    }

    private static void applySystem(MessageCreateParams.Builder builder, LlmRequest request) {
        String stable = request.systemStable();
        String volatilePart = request.systemVolatile();

        // Cache the static instruction body as an ephemeral block so it is not re-billed
        // across the multi-iteration tool loop and subsequent turns of the same conversation.
        if (request.cacheSystem() && stable != null && !stable.isBlank()) {
            List<TextBlockParam> blocks = new ArrayList<>();
            blocks.add(TextBlockParam.builder()
                    .text(stable)
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build());
            if (volatilePart != null && !volatilePart.isBlank()) {
                blocks.add(TextBlockParam.builder().text(volatilePart).build());
            }
            builder.systemOfTextBlockParams(blocks);
            return;
        }

        String combined = request.system();
        if (combined != null) {
            builder.system(combined);
        }
    }

    private static MessageParam toMessageParam(LlmMessage msg) {
        return switch (msg.kind()) {
            case TEXT -> MessageParam.builder()
                    .role(msg.role() == LlmMessage.Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                    .content(MessageParam.Content.ofString(msg.text()))
                    .build();
            case TOOL_USE -> MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content(MessageParam.Content.ofBlockParams(List.of(
                            ContentBlockParam.ofToolUse(
                                    ToolUseBlockParam.builder()
                                            .name(msg.toolCall().name())
                                            .id(msg.toolCall().id())
                                            .input(JsonValue.from(msg.toolCall().input()))
                                            .build()
                            )
                    )))
                    .build();
            case TOOL_RESULT -> MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(List.of(
                            ContentBlockParam.ofToolResult(
                                    ToolResultBlockParam.builder()
                                            .toolUseId(msg.toolResultId())
                                            .content(msg.toolResultContent())
                                            .build()
                            )
                    )))
                    .build();
        };
    }

    private static Tool toTool(LlmTool llmTool) {
        Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder()
                .properties(JsonValue.from(llmTool.properties()));
        if (!llmTool.required().isEmpty()) {
            schemaBuilder.required(llmTool.required());
        }
        return Tool.builder()
                .name(llmTool.name())
                .description(llmTool.description())
                .inputSchema(schemaBuilder.build())
                .build();
    }

    private static LlmResponse toResponse(Message message) {
        Usage u = message.usage();
        LOGGER.infof("[anthropic] input=%d cache_write=%d cache_read=%d output=%d",
                u.inputTokens(),
                u.cacheCreationInputTokens().orElse(0L),
                u.cacheReadInputTokens().orElse(0L),
                u.outputTokens());

        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .findFirst().orElse("");

        LlmToolCall toolCall = message.content().stream()
                .flatMap(block -> block.toolUse().stream())
                .findFirst()
                .map(tu -> new LlmToolCall(tu.id(), tu.name(), extractInput(tu._input())))
                .orElse(null);

        return new LlmResponse(text, toolCall);
    }

    static Map<String, Object> extractInput(JsonValue inputVal) {
        Object converted = toObject(inputVal);
        if (!(converted instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        return map.entrySet().stream()
                .filter(e -> e.getKey() instanceof String)
                .collect(Collectors.toMap(
                        e -> (String) e.getKey(),
                        Map.Entry::getValue
                ));
    }

    private static Object toObject(JsonValue value) {
        var str = value.asString();
        if (str.isPresent()) return str.get();
        var bool = value.asBoolean();
        if (bool.isPresent()) return bool.get();
        var num = value.asNumber();
        if (num.isPresent()) return num.get();
        var arr = value.asArray();
        if (arr.isPresent()) {
            Object rawArray = arr.get();
            if (rawArray instanceof List<?> list) {
                return list.stream()
                        .map(item -> item instanceof JsonValue jsonValue ? toObject(jsonValue) : item)
                        .toList();
            }
        }
        var obj = value.asObject();
        if (obj.isPresent()) {
            Object rawObject = obj.get();
            if (rawObject instanceof Map<?, ?> map) {
                return map.entrySet().stream()
                        .filter(e -> e.getKey() instanceof String)
                        .collect(Collectors.toMap(
                                e -> (String) e.getKey(),
                                e -> e.getValue() instanceof JsonValue jsonValue ? toObject(jsonValue) : e.getValue()
                        ));
            }
        }
        return null;
    }
}
