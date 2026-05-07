package com.semantyca.jesoos.outbound;

import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class InternalRestCall {

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<Void> sendAivoxCommand(String brand, String command) {
        if (brand == null || brand.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Brand must be provided"));
        }

        String endpoint = String.format("%s/aivox/command/queue", config.getAivoxUrl());

        return webClient
                .postAbs(endpoint)
                .addQueryParam("brand", brand)
                .putHeader("Content-Type", "application/json")
                .send()
                .onItem().transform(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    }
                    throw new RuntimeException(String.format(
                            "Failed to send Aivox command '%s' for brand '%s'. HTTP %d: %s",
                            command,
                            brand,
                            response.statusCode(),
                            response.bodyAsString()
                    ));
                });
    }

    public Uni<Void> addSongToQueue(String brand, java.util.UUID songId, String mixingType, int priority, String introFilePath, float introGain) {
        if (brand == null || brand.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Brand must be provided"));
        }

        String endpoint = String.format("%s/aivox/command/queue", config.getAivoxUrl());

        io.vertx.core.json.JsonObject body = new io.vertx.core.json.JsonObject()
                .put("brand", brand)
                .put("songId", songId.toString())
                .put("mixingType", mixingType)
                .put("priority", priority)
                .put("introGain", introGain);
        if (introFilePath != null) {
            body.put("introFilePath", introFilePath);
        }

        return webClient
                .postAbs(endpoint)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(io.vertx.mutiny.core.buffer.Buffer.buffer(body.encode()))
                .onItem().transform(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    }
                    throw new RuntimeException(String.format(
                            "Failed to add song to queue for brand '%s'. HTTP %d: %s",
                            brand,
                            response.statusCode(),
                            response.bodyAsString()
                    ));
                });
    }
}