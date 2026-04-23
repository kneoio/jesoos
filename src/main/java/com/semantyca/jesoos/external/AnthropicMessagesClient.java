package com.semantyca.jesoos.external;

import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AnthropicMessagesClient {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    /**
     * Calls Anthropic Messages API with a single user text message and optional system prompt.
     */
    public Uni<AnthropicTextMessageResult> createTextMessage(
            String model,
            long maxTokens,
            String systemPrompt,
            String userMessage
    ) {
        JsonObject body = new JsonObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("system", systemPrompt)
                .put("messages", new JsonArray()
                        .add(new JsonObject()
                                .put("role", "user")
                                .put("content", userMessage)));

        return webClient
                .postAbs(MESSAGES_URL)
                .putHeader("x-api-key", config.getAnthropicApiKey())
                .putHeader("anthropic-version", ANTHROPIC_VERSION)
                .putHeader("Content-Type", "application/json")
                .timeout(60_000)
                .sendJsonObject(body)
                .map(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException(
                                "Anthropic API error: " + response.statusCode() + " - " + response.bodyAsString());
                    }
                    JsonObject json = response.bodyAsJsonObject();
                    if (json == null) {
                        throw new RuntimeException("Empty Anthropic response body");
                    }
                    JsonArray content = json.getJsonArray("content");
                    if (content == null || content.isEmpty()) {
                        throw new RuntimeException("No content in Anthropic response");
                    }
                    String text = null;
                    for (int i = 0; i < content.size(); i++) {
                        JsonObject block = content.getJsonObject(i);
                        if (block != null && "text".equals(block.getString("type"))) {
                            text = block.getString("text");
                            break;
                        }
                    }
                    if (text == null) {
                        throw new RuntimeException("No text generated from AI");
                    }
                    JsonObject usage = json.getJsonObject("usage");
                    int inputTokens = usage != null ? usage.getInteger("input_tokens", 0) : 0;
                    int outputTokens = usage != null ? usage.getInteger("output_tokens", 0) : 0;
                    return new AnthropicTextMessageResult(text, inputTokens, outputTokens);
                });
    }
}
