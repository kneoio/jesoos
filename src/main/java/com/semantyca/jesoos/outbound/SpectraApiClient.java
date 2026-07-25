package com.semantyca.jesoos.outbound;

import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.multipart.MultipartForm;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;

/**
 * Client for the spectra audio-analysis service. Kept uniform with datanest's SpectraApiClient
 * (same shape / config accessor) so the two can later be lifted into 2next core as one shared client.
 *
 * <p>This service uses spectra's synchronous {@code POST /assess}: it uploads a local audio file and
 * gets the musical analysis back in the response body (BPM, key/scale, moods, genres, danceability
 * plus an {@code is_music} verdict and the weak AI-generation metadata check). Unlike the async
 * {@code /analyze}, it writes nothing to the database — it is meant for pre-save assessment of an
 * uploaded track that has no SoundFragment yet.
 */
@ApplicationScoped
public class SpectraApiClient {

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<JsonObject> assess(Path file) {
        MultipartForm form = MultipartForm.create()
                .binaryFileUpload("file", file.getFileName().toString(),
                        file.toAbsolutePath().toString(), "application/octet-stream");

        return webClient
                .postAbs(config.getSpectraUrl() + "/assess")
                .timeout(120000) // Essentia + TF analysis is slow; well over the default WebClient timeout.
                .sendMultipartForm(form)
                .map(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new RuntimeException("Spectra assess error: " +
                                response.statusCode() + " - " + response.bodyAsString());
                    }
                    JsonObject body = response.bodyAsJsonObject();
                    if (body == null) {
                        throw new RuntimeException("Spectra assess returned an empty body");
                    }
                    return body;
                });
    }
}
