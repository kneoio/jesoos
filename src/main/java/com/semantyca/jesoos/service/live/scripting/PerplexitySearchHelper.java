package com.semantyca.jesoos.service.live.scripting;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.jesoos.agent.PerplexityApiClient;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;

@ApplicationScoped
public class PerplexitySearchHelper {

    @Inject
    PerplexityApiClient perplexityApiClient;

    public PerplexitySearchHelper() {
    }

    public PerplexitySearchHelper(PerplexityApiClient perplexityApiClient) {
        this.perplexityApiClient = perplexityApiClient;
    }

    public Uni<JsonObject> search(String query, List<LanguageCode> languages, List<String> domains) {
        return perplexityApiClient.search(query, languages, domains, false)
                .onFailure().recoverWithItem(e ->
                        new JsonObject().put("error", "Search failed: " + e.getMessage())
                );
    }

    public Uni<JsonObject> search(String query) {
        return search(query, List.of(), List.of());
    }

    public JsonObject searchBlocking(String query, List<LanguageCode> languages, List<String> domains) {
        return search(query, languages, domains)
                .await().atMost(Duration.ofSeconds(30));
    }

    public JsonObject searchBlocking(String query) {
        return searchBlocking(query, List.of(), List.of());
    }

    public String searchTextBlocking(String query, List<LanguageCode> languages, List<String> domains) {
        return searchBlocking(query, languages, domains).encode();
    }

    public String searchTextBlocking(String query) {
        return searchTextBlocking(query, List.of(), List.of());
    }
}
