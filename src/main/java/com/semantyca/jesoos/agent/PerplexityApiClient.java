package com.semantyca.jesoos.agent;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.jesoos.config.PerplexityApiConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class PerplexityApiClient {

    @Inject
    PerplexityApiConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        WebClientOptions options = new WebClientOptions()
                .setSsl(true)
                .setTrustAll(true)
                .setVerifyHost(false);
        this.webClient = WebClient.create(vertx, options);
    }

    public Uni<JsonObject> search(String query, List<LanguageCode> languages, List<String> domains, boolean onlySummaries) {

        String languageInstruction = !languages.isEmpty()
                ? " Respond in " + languages.get(0).name() + " language."
                : "";

        JsonObject requestBody;

        if (onlySummaries) {
            JsonObject schema = new JsonObject()
                    .put("type", "object")
                    .put("properties", new JsonObject()
                            .put("title", new JsonObject().put("type", "string"))
                            .put("summary", new JsonObject().put("type", "string"))
                            .put("articles", new JsonObject()
                                    .put("type", "array")
                                    .put("items", new JsonObject()
                                            .put("type", "object")
                                            .put("properties", new JsonObject()
                                                    .put("title", new JsonObject().put("type", "string"))
                                                    .put("url", new JsonObject().put("type", "string"))
                                                    .put("date", new JsonObject().put("type", "string"))
                                                    .put("source", new JsonObject().put("type", "string"))
                                                    .put("content", new JsonObject().put("type", "string"))
                                            )
                                    )
                            )
                    )
                    .put("required", new JsonArray().add("title").add("summary").add("articles"));

            requestBody = new JsonObject()
                    .put("model", "sonar-pro")
                    .put("response_format", new JsonObject()
                            .put("type", "json_schema")
                            .put("json_schema", new JsonObject()
                                    .put("name", "news_response")
                                    .put("schema", schema)
                                    .put("strict", true)
                            )
                    )
                    .put("messages", List.of(
                            new JsonObject()
                                    .put("role", "user")
                                    .put("content", query + languageInstruction)
                    ));
        } else {
            requestBody = new JsonObject()
                    .put("model", "sonar-pro")
                    .put("messages", List.of(
                            new JsonObject()
                                    .put("role", "user")
                                    .put("content", query + languageInstruction)
                    ));
        }

        if (!languages.isEmpty()) {
            requestBody.put("search_language_filter", languages.stream().map(LanguageCode::getAltCode).toList());
        }

        if (!domains.isEmpty()) {
            requestBody.put("search_domain_filter", domains);
        }

        return webClient
                .postAbs(config.getBaseUrl() + "/chat/completions")
                .putHeader("Authorization", "Bearer " + config.getApiKey())
                .putHeader("Content-Type", "application/json")
                .timeout(30000)
                .sendJsonObject(requestBody)
                .onItem().transform(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Perplexity API error: " +
                                response.statusCode() + " - " + response.bodyAsString());
                    }

                    String content = response.bodyAsJsonObject()
                            .getJsonArray("choices")
                            .getJsonObject(0)
                            .getJsonObject("message")
                            .getString("content");

                    return onlySummaries ? new JsonObject(content) : new JsonObject().put("response", content);
                });
    }
}
