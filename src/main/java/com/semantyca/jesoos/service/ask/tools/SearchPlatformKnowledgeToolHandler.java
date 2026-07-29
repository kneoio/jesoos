package com.semantyca.jesoos.service.ask.tools;

import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class SearchPlatformKnowledgeToolHandler {

    private static final Logger LOG = Logger.getLogger(SearchPlatformKnowledgeToolHandler.class);
    private static final int MAX_HITS = 6;
    private static final String CORPUS;

    static {
        String loaded;
        try {
            loaded = ResourceUtil.loadResourceAsString("ask/platform-knowledge.md");
            if (loaded.isBlank()) {
                loaded = ResourceUtil.loadResourceAsString("/ask/platform-knowledge.md");
            }
        } catch (Exception e) {
            LOG.warnf("Failed to load platform-knowledge.md: %s", e.getMessage());
            loaded = "";
        }
        CORPUS = loaded;
    }

    private SearchPlatformKnowledgeToolHandler() {}

    public static Uni<ToolNodeResult> execute(Map<String, Object> input) {
        String query = ((String) input.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "query is required").encode()));
        }
        if (CORPUS.isBlank()) {
            return Uni.createFrom().item(ToolNodeResult.ok(
                    new JsonObject().put("ok", false).put("error", "Knowledge corpus unavailable").encode()));
        }

        List<String> terms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(t -> t.replaceAll("[^a-z0-9_-]", ""))
                .filter(t -> t.length() >= 2)
                .distinct()
                .toList();

        String[] sections = CORPUS.split("(?m)^## ");
        List<JsonObject> hits = new ArrayList<>();
        for (String raw : sections) {
            if (raw.isBlank()) continue;
            String section = raw.startsWith("#") ? raw : "## " + raw;
            String lower = section.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) {
                if (lower.contains(term)) score++;
            }
            if (score == 0 && terms.isEmpty() && lower.contains(query.toLowerCase(Locale.ROOT))) {
                score = 1;
            }
            if (score > 0) {
                hits.add(new JsonObject()
                        .put("score", score)
                        .put("snippet", section.strip().length() > 1200
                                ? section.strip().substring(0, 1200) + "…"
                                : section.strip()));
            }
        }

        hits.sort((a, b) -> Integer.compare(b.getInteger("score"), a.getInteger("score")));
        if (hits.size() > MAX_HITS) {
            hits = hits.subList(0, MAX_HITS);
        }

        JsonArray results = new JsonArray();
        hits.forEach(h -> results.add(h.getString("snippet")));

        return Uni.createFrom().item(ToolNodeResult.ok(
                new JsonObject()
                        .put("ok", true)
                        .put("query", query)
                        .put("count", results.size())
                        .put("results", results)
                        .encode()));
    }
}
