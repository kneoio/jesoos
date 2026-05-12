package com.semantyca.jesoos.external;

import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

@ApplicationScoped
@Typed(GroqTextClient.class)
public class GroqTextClient implements LlmTextClient {

    private static final String CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    @Override
    public Uni<LlmTextResult> createTextMessage(String model, long maxTokens, String systemPrompt, String userMessage) {
        JsonObject body = new JsonObject()
                .put("model", model)
                //.put("max_tokens", (int) maxTokens)  //for the old llms
                .put("max_completion_tokens", (int) maxTokens)
                .put("messages", new JsonArray()
                        .add(new JsonObject().put("role", "system").put("content", systemPrompt))
                        .add(new JsonObject().put("role", "user").put("content", userMessage)));

        return webClient
                .postAbs(CHAT_URL)
                .putHeader("Authorization", "Bearer " + config.getGroqApiKey().orElseThrow(() -> new IllegalStateException("Groq API key not configured")))
                .putHeader("Content-Type", "application/json")
                .timeout(60_000)
                .sendJsonObject(body)
                .map(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Groq API error: " + response.statusCode() + " - " + response.bodyAsString());
                    }
                    JsonObject json = response.bodyAsJsonObject();
                    if (json == null) {
                        throw new RuntimeException("Empty Groq response body");
                    }
                    JsonArray choices = json.getJsonArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        throw new RuntimeException("No choices in Groq response");
                    }
                    String text = choices.getJsonObject(0)
                            .getJsonObject("message")
                            .getString("content");
                    if (text == null) {
                        throw new RuntimeException("No text generated from Groq");
                    }
                    JsonObject usage = json.getJsonObject("usage");
                    int inputTokens = usage != null ? usage.getInteger("prompt_tokens", 0) : 0;
                    int outputTokens = usage != null ? usage.getInteger("completion_tokens", 0) : 0;
                    return new LlmTextResult(text, inputTokens, outputTokens);
                });
    }
}
