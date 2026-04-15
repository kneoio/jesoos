package com.semantyca.jesoos.external;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class FishAudioClient implements TextToSpeechClient {

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
    public Uni<byte[]> textToSpeech(String text, String voiceId, String modelId, LanguageTag languageTag) {
        String endpoint = "https://api.fish.audio/v1/tts";

        JsonObject payload = new JsonObject();
        payload.put("text", text);
        payload.put("reference_id", voiceId);
        payload.put("format", "mp3");

        return webClient
                .postAbs(endpoint)
                .putHeader("Authorization", "Bearer " + config.getFishAudioApiKey())
                .putHeader("Content-Type", "application/json")
                .putHeader("model", modelId != null ? modelId : "s2-pro")
                .sendJsonObject(payload)
                .map(response -> {
                    if (response.statusCode() == 200) {
                        return response.bodyAsBuffer().getBytes();
                    } else {
                        throw new RuntimeException("Fish Audio API error - HTTP " + response.statusCode() + ": " + response.bodyAsString());
                    }
                });
    }
}