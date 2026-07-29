package com.semantyca.jesoos.service.knowledge;

import com.semantyca.jesoos.service.chat.ToolNodeResult;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SearchPlatformKnowledgeToolHandler {

    private SearchPlatformKnowledgeToolHandler() {}

    public static Uni<ToolNodeResult> execute(Map<String, Object> input, KnowledgeBase knowledgeBase,
                                             Set<Audience> audiences) {
        String query = ((String) input.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "query is required").encode()));
        }
        if (knowledgeBase.isEmpty()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Knowledge base unavailable").encode()));
        }

        List<KnowledgeBase.Hit> hits = knowledgeBase.search(query, KnowledgeBase.DEFAULT_MAX_HITS, audiences);
        JsonArray results = new JsonArray();
        hits.forEach(hit -> results.add(new JsonObject()
                .put("title", hit.concept().title())
                .put("type", hit.concept().type())
                .put("path", hit.concept().path())
                .put("description", hit.concept().description())
                .put("tags", new JsonArray(hit.concept().tags()))
                .put("content", hit.snippet())));

        return Uni.createFrom().item(ToolNodeResult.ok(
                new JsonObject()
                        .put("ok", true)
                        .put("query", query)
                        .put("count", results.size())
                        .put("results", results)
                        .encode()));
    }
}
