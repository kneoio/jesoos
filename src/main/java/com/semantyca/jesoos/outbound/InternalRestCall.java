package com.semantyca.jesoos.outbound;

import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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

    public Uni<JsonArray> getQueueFromAivox(String brand) {
        String endpoint = String.format("%s/aivox/info/queue/%s", config.getAivoxUrl(), brand);
        return webClient.getAbs(endpoint)
                .putHeader("X-Client-ID", "mixpla-web")
                .send()
                .map(response -> {
                    if (response.statusCode() != 200) return new JsonArray();
                    JsonObject body = response.bodyAsJsonObject();
                    if (body == null) return new JsonArray();
                    JsonArray fullQueue = body.getJsonArray("fullQueue");
                    if (fullQueue == null) return new JsonArray();
                    JsonArray result = new JsonArray();
                    for (int i = 0; i < fullQueue.size(); i++) {
                        JsonObject entry = fullQueue.getJsonObject(i);
                        if (entry == null) continue;
                        JsonObject songInfo = entry.getJsonObject("songInfo");
                        if (songInfo == null) continue;
                        JsonObject tech = entry.getJsonObject("tech");
                        String queueType = tech != null ? tech.getString("queueType", "") : "";
                        result.add(new JsonObject()
                                .put("title", songInfo.getString("title", ""))
                                .put("artist", songInfo.getString("artist", ""))
                                .put("labels", songInfo.getValue("labels", ""))
                                .put("queueType", queueType));
                    }
                    return result;
                })
                .onFailure().recoverWithItem(new JsonArray());
    }

    public Uni<Void> addSongToQueue(SongQueueMessageDTO dto) {
        if (dto.getBrandSlug() == null || dto.getBrandSlug().isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Brand must be provided"));
        }

        String endpoint = String.format("%s/aivox/command/queue", config.getAivoxUrl());

        return webClient
                .postAbs(endpoint)
                .putHeader("X-Client-ID", "mixpla-web")
                .putHeader("Content-Type", "application/json")
                .sendJson(dto)
                .onItem().transform(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    }
                    throw new RuntimeException(String.format(
                            "Failed to add song to queue for brand '%s'. HTTP %d: %s",
                            dto.getBrandSlug(),
                            response.statusCode(),
                            response.bodyAsString()
                    ));
                });
    }
}