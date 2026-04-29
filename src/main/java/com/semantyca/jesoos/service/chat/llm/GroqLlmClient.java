package com.semantyca.jesoos.service.chat.llm;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public class GroqLlmClient implements LlmClient {
    private static final String CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public GroqLlmClient(String apiKey, String model) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public CompletionStage<Message> createMessage(MessageCreateParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> body = buildBaseRequest(params, false);
                String payload = objectMapper.writeValueAsString(body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(CHAT_COMPLETIONS_URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofSeconds(90))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Groq request failed: HTTP " + response.statusCode() + " - " + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choice = root.path("choices").path(0);
                JsonNode messageNode = choice.path("message");

                Message.Builder resultBuilder = Message.builder()
                        .id(root.path("id").asText(UUID.randomUUID().toString()))
                        .model(root.path("model").asText(model));

                String content = messageNode.path("content").asText("");
                if (!content.isBlank()) {
                    resultBuilder.addContent(TextBlock.builder().text(content).build());
                }

                JsonNode toolCalls = messageNode.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode toolCall : toolCalls) {
                        String toolId = toolCall.path("id").asText(UUID.randomUUID().toString());
                        JsonNode function = toolCall.path("function");
                        String toolName = function.path("name").asText();
                        String argsText = function.path("arguments").asText("{}");
                        Object argsValue = objectMapper.readValue(argsText, Object.class);
                        resultBuilder.addContent(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(JsonValue.from(argsValue))
                                        .build()
                        );
                    }
                }

                return resultBuilder.build();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create Groq message", e);
            }
        });
    }

    @Override
    public CompletionStage<String> streamText(MessageCreateParams params, Consumer<String> chunkConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> body = buildBaseRequest(params, true);
                String payload = objectMapper.writeValueAsString(body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(CHAT_COMPLETIONS_URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IllegalStateException("Groq streaming request failed: HTTP " + response.statusCode() + " - " + errorBody);
                }

                StringBuilder fullText = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) {
                            continue;
                        }
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        JsonNode event = objectMapper.readTree(data);
                        JsonNode delta = event.path("choices").path(0).path("delta");
                        String content = delta.path("content").asText("");
                        if (!content.isEmpty()) {
                            fullText.append(content);
                            chunkConsumer.accept(content);
                        }
                    }
                }
                return fullText.toString();
            } catch (Exception e) {
                throw new RuntimeException("Failed to stream Groq response", e);
            }
        });
    }

    private Map<String, Object> buildBaseRequest(MessageCreateParams params, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", params.temperature().orElse(1.0));
        body.put("max_completion_tokens", params.maxTokens());
        body.put("top_p", params.topP().orElse(1.0));
        body.put("stream", stream);
        body.put("reasoning_effort", "medium");
        body.put("stop", null);

        List<Map<String, Object>> messages = new ArrayList<>();
        params.system().ifPresent(system -> {
            String content = system.isString() ? system.asString() : system.toString();
            messages.add(Map.of("role", "system", "content", content));
        });
        for (MessageParam message : params.messages()) {
            messages.add(Map.of(
                    "role", toOpenAiRole(message.role()),
                    "content", toOpenAiContent(message.content())
            ));
        }
        body.put("messages", messages);

        if (params.tools().isPresent()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolUnion toolUnion : params.tools().get()) {
                if (!toolUnion.isTool()) {
                    continue;
                }
                Tool tool = toolUnion.asTool();
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", tool.name());
                function.put("description", tool.description().orElse(""));
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("type", tool.inputSchema()._type().convert(Object.class));
                parameters.put("properties", tool.inputSchema()._properties().convert(Object.class));
                tool.inputSchema().required().ifPresent(required -> parameters.put("required", required));
                function.put("parameters", parameters);

                Map<String, Object> toolPayload = new LinkedHashMap<>();
                toolPayload.put("type", "function");
                toolPayload.put("function", function);
                tools.add(toolPayload);
            }
            if (!tools.isEmpty()) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
        }
        return body;
    }

    private String toOpenAiRole(MessageParam.Role role) {
        String value = role.toString().toLowerCase(Locale.ROOT);
        if (value.contains("assistant")) {
            return "assistant";
        }
        return "user";
    }

    private String toOpenAiContent(MessageParam.Content content) {
        if (content.isString()) {
            return content.asString();
        }
        return content.toString();
    }
}
