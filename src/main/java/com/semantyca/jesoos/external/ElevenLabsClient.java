package com.semantyca.jesoos.external;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class ElevenLabsClient implements TTSClient {

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<byte[]> textToSpeech(String text, String voiceId, String modelId, LanguageTag languageTag) {
        String endpoint = String.format(
            "https://api.elevenlabs.io/v1/text-to-speech/%s?output_format=%s",
            voiceId,
            config.getElevenLabsOutputFormat()
        );

        JsonObject payload = new JsonObject();
        payload.put("text", text);
        payload.put("model_id", modelId);

        return webClient
                .postAbs(endpoint)
                .putHeader("xi-api-key", config.getElevenLabsApiKey())
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(payload)
                .map(response -> {
                    if (response.statusCode() == 200) {
                        return response.bodyAsBuffer().getBytes();
                    } else {
                        throw new RuntimeException("ElevenLabs API error - HTTP " + response.statusCode() + ": " + response.bodyAsString());
                    }
                });
    }

    public record DialogueSegment(String voiceId, String text) {}

    public Uni<byte[]> textToDialogue(List<DialogueSegment> segments, String modelId) {
        String endpoint = String.format(
            "https://api.elevenlabs.io/v1/text-to-dialogue?output_format=%s",
            config.getElevenLabsOutputFormat()
        );

        JsonArray inputs = new JsonArray();
        for (DialogueSegment segment : segments) {
            inputs.add(new JsonObject()
                    .put("text", segment.text())
                    .put("voice_id", segment.voiceId()));
        }

        JsonObject payload = new JsonObject();
        payload.put("inputs", inputs);
        payload.put("model_id", modelId);

        return webClient
                .postAbs(endpoint)
                .putHeader("xi-api-key", config.getElevenLabsApiKey())
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(payload)
                .map(response -> {
                    if (response.statusCode() == 200) {
                        return response.bodyAsBuffer().getBytes();
                    } else {
                        throw new RuntimeException("ElevenLabs dialogue API error - HTTP " + response.statusCode() + ": " + response.bodyAsString());
                    }
                });
    }
}
