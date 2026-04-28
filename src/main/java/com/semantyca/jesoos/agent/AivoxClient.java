package com.semantyca.jesoos.agent;

import com.semantyca.jesoos.config.JesoosConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AivoxClient {
    private static final Logger LOGGER = Logger.getLogger(AivoxClient.class);

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<Void> startOtsStation(String otsSlugName, String masterBrandSlug) {
        String endpoint = config.getAgentUrl() + "/aivox/command/start-ots";
        return webClient.postAbs(endpoint)
                .addQueryParam("otsSlug", otsSlugName)
                .addQueryParam("masterBrand", masterBrandSlug)
                .putHeader("Content-Type", "application/json")
                .send()
                .onItem().transform(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return (Void) null;
                    }
                    throw new RuntimeException(String.format(
                            "aivox start-ots failed for otsSlug '%s': HTTP %d: %s",
                            otsSlugName, response.statusCode(), response.bodyAsString()));
                });
    }

    public Uni<Void> stopOtsStation(String otsSlugName) {
        String endpoint = config.getAgentUrl() + "/aivox/command/stop-ots";
        return webClient.deleteAbs(endpoint)
                .addQueryParam("otsSlug", otsSlugName)
                .putHeader("Content-Type", "application/json")
                .send()
                .onItem().transform(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return (Void) null;
                    }
                    throw new RuntimeException(String.format(
                            "aivox stop-ots failed for otsSlug '%s': HTTP %d: %s",
                            otsSlugName, response.statusCode(), response.bodyAsString()));
                });
    }
}
