package com.semantyca.jesoos.agent;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.cnst.TranslationType;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.agentrest.AgentRespDTO;
import com.semantyca.mixpla.model.cnst.LlmType;
import com.semantyca.officeframe.model.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;

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

    public Uni<String> callRadioDjAgent(String provider, String template, Map<String, String> variables) {
        String endpoint = config.getAgentUrl() + "/radio_dj/test/" + provider;

        JsonObject payload = new JsonObject();
        payload.put("template", template);
        payload.put("variables", variables != null ? variables : new HashMap<>());

        return webClient
                .postAbs(endpoint)
                .putHeader("Content-Type", "application/json")
                .putHeader("X-API-Key", config.getAgentApiKey())
                .sendJsonObject(payload)
                .map(response -> {
                    if (response.statusCode() == 200) {
                        return response.bodyAsString();
                    } else {
                        throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.bodyAsString());
                    }
                });
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
                .putHeader("X-API-Key", config.getAgentApiKey())
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

    public Uni<AgentRespDTO> translate(String toTranslate, TranslationType translationType, LanguageTag languageTag, CountryCode countryCode) {
        String endpoint = config.getAgentUrl() + "/translate";

        JsonObject payload = new JsonObject();
        payload.put("toTranslate", toTranslate);
        payload.put("translationType", translationType);
        payload.put("language", languageTag.name());
        payload.put("country", countryCode.name());

        return webClient
                .postAbs(endpoint)
                .putHeader("Content-Type", "application/json")
                .putHeader("X-API-Key", config.getAgentApiKey())
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

    public Uni<Boolean> checkHealth() {
        String endpoint = config.getAgentUrl() + "/health";
        
        return webClient
                .getAbs(endpoint)
                .timeout(3000)
                .send()
                .map(response -> response.statusCode() == 200)
                .onFailure().recoverWithItem(false);
    }
}