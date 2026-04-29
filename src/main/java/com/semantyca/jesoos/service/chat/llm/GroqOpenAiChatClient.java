package com.semantyca.jesoos.service.chat.llm;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Standalone Groq OpenAI-compatible chat client.
 * This class is additive and does not alter existing runtime flows.
 */
public class GroqOpenAiChatClient {
    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public GroqOpenAiChatClient(String apiKey, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.model = model;
    }

    public CompletionStage<String> create(String userContent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String payload = objectMapper.writeValueAsString(buildBody(userContent, false));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(URL))
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
                return root.path("choices").path(0).path("message").path("content").asText("");
            } catch (Exception e) {
                throw new RuntimeException("Groq create failed: " + e.getMessage(), e);
            }
        });
    }

    public CompletionStage<String> createStreaming(String userContent, Consumer<String> chunkHandler) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String payload = objectMapper.writeValueAsString(buildBody(userContent, true));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IllegalStateException("Groq stream failed: HTTP " + response.statusCode() + " - " + errorBody);
                }

                StringBuilder full = new StringBuilder();
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
                        String delta = event.path("choices").path(0).path("delta").path("content").asText("");
                        if (!delta.isEmpty()) {
                            full.append(delta);
                            chunkHandler.accept(delta);
                        }
                    }
                }
                return full.toString();
            } catch (Exception e) {
                throw new RuntimeException("Groq stream failed: " + e.getMessage(), e);
            }
        });
    }

    private Map<String, Object> buildBody(String userContent, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", new Object[]{
                Map.of("role", "user", "content", userContent == null ? "" : userContent)
        });
        body.put("model", model);
        body.put("temperature", 1);
        body.put("max_completion_tokens", 8192);
        body.put("top_p", 1);
        body.put("stream", stream);
        body.put("reasoning_effort", "medium");
        body.put("stop", null);
        return body;
    }
}
