package com.semantyca.jesoos.external;

import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.agentrest.AgentRespDTO;
import com.semantyca.mixpla.model.cnst.LlmType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AgentClient {

    @Inject
    JesoosConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<AgentRespDTO> testPrompt(String prompt, String draft, LlmType llmType) {
        String endpoint = config.getAgentUrl() + "/prompt/test";

        JsonObject payload = new JsonObject();
        payload.put("prompt", prompt);
        payload.put("draft", draft);
        payload.put("llm", llmType.name());

        return webClient
                .postAbs(endpoint)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(payload)
                .map(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject body = response.bodyAsJsonObject();
                        if (body == null) {
                            throw new RuntimeException("Empty response body");
                        }
                        return body.mapTo(AgentRespDTO.class);
                    } else {
                        throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.bodyAsString());
                    }
                });
    }
}