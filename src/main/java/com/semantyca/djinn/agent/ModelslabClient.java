package com.semantyca.djinn.agent;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.config.BroadcasterConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ModelslabClient implements TextToSpeechClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelslabClient.class);

    @Inject
    BroadcasterConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<byte[]> textToSpeech(String text, String voiceId, String modelId, LanguageTag languageTag) {
        LOGGER.info("Starting Modelslab TTS generation - Voice: {}, Text length: {} chars", voiceId, text.length());

        String langTag = getLangString(languageTag);
        JsonObject payload = new JsonObject()
                .put("key", config.getModelslabApiKey())
                .put("prompt", text)
                .put("voice_id", voiceId)
                .put("language", langTag)
                .put("speed", 1)
                .put("emotion", true);

        String endpoint = "https://modelslab.com/api/v6/voice/text_to_speech";

        return webClient
                .postAbs(endpoint)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(payload)
                .flatMap(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Modelslab API error - HTTP " + response.statusCode() + ": " + response.bodyAsString());
                    }
                    
                    JsonObject jsonResponse = response.bodyAsJsonObject();
                    String status = jsonResponse.getString("status");
                    
                    if ("success".equals(status)) {
                        String audioUrl = extractAudioUrl(jsonResponse);
                        return downloadAudio(audioUrl);
                    } else if ("processing".equals(status)) {
                        String fetchUrl = jsonResponse.getString("fetch_result");
                        return pollForCompletion(fetchUrl, 200, 2000);
                    } else {
                        LOGGER.error("Modelslab failed. voice: {}, language: {}", voiceId, langTag);
                        throw new RuntimeException("Modelslab API failed: " + jsonResponse.encode());
                    }
                });
    }

    private String extractAudioUrl(JsonObject jsonResponse) {
        if (jsonResponse.containsKey("output") && !jsonResponse.getJsonArray("output").isEmpty()) {
            return jsonResponse.getJsonArray("output").getString(0);
        }
        if (jsonResponse.containsKey("proxy_links") && !jsonResponse.getJsonArray("proxy_links").isEmpty()) {
            return jsonResponse.getJsonArray("proxy_links").getString(0);
        }
        if (jsonResponse.containsKey("future_links") && !jsonResponse.getJsonArray("future_links").isEmpty()) {
            return jsonResponse.getJsonArray("future_links").getString(0);
        }
        throw new RuntimeException("No audio URL found in response: " + jsonResponse.encode());
    }

    private Uni<byte[]> pollForCompletion(String fetchUrl, int maxAttempts, long delayMs) {
        return Uni.createFrom().item(0)
                .onItem().transformToUni(attempt -> pollOnce(fetchUrl, attempt, maxAttempts, delayMs));
    }

    private Uni<byte[]> pollOnce(String fetchUrl, int attempt, int maxAttempts, long delayMs) {
        if (attempt >= maxAttempts) {
            return Uni.createFrom().failure(new RuntimeException("Modelslab TTS timeout after " + maxAttempts + " attempts"));
        }
        
        // Log progress every 20 attempts
        if (attempt % 20 == 0 && attempt > 0) {
            LOGGER.info("Modelslab TTS polling progress: {}/{} attempts ({} seconds elapsed)", 
                    attempt, maxAttempts, (attempt * delayMs) / 1000);
        }

        return Uni.createFrom().item(attempt)
                .onItem().delayIt().by(java.time.Duration.ofMillis(delayMs))
                .onItem().transformToUni(i -> webClient
                        .postAbs(fetchUrl)
                        .putHeader("Content-Type", "application/json")
                        .sendJsonObject(new JsonObject().put("key", config.getModelslabApiKey()))
                        .flatMap(response -> {
                            if (response.statusCode() != 200) {
                                throw new RuntimeException("Modelslab fetch error - HTTP " + response.statusCode());
                            }

                            JsonObject jsonResponse = response.bodyAsJsonObject();
                            String status = jsonResponse.getString("status");

                            if ("success".equals(status)) {
                                String audioUrl = extractAudioUrl(jsonResponse);
                                return downloadAudio(audioUrl);
                            } else if ("processing".equals(status)) {
                                return pollOnce(fetchUrl, attempt + 1, maxAttempts, delayMs);
                            } else {
                                throw new RuntimeException("Modelslab fetch failed: " + jsonResponse.encode());
                            }
                        }));
    }

    private Uni<byte[]> downloadAudio(String audioUrl) {
        return webClient
                .getAbs(audioUrl)
                .send()
                .map(audioResponse -> {
                    if (audioResponse.statusCode() == 200) {
                        return audioResponse.bodyAsBuffer().getBytes();
                    } else {
                        throw new RuntimeException("Failed to download audio from Modelslab URL: " + audioUrl);
                    }
                });
    }

    private static String getLangString(LanguageTag tag) {
        if (tag == LanguageTag.EN_US) {
            return "american english";
        }
        if (tag == LanguageTag.EN_GB) {
            return "british english";
        }
        if (tag == LanguageTag.ES_ES){
            return "spanish";
        }
        if (tag == LanguageTag.JA_JP) {
            return "japanese";
        }
        if (tag == LanguageTag.ZH_CN) {
            return "mandarin chinese";
        }
        if (tag == LanguageTag.FR_FR) {
            return "french";
        }
        if (tag == LanguageTag.PT_BR) {
            return "brazilian portuguese";
        }
        if (tag == LanguageTag.HI_IN) {
            return "hindi";
        }
        if (tag == LanguageTag.IT_IT) {
            return "italian";
        }
        return "american english";
    }
}
