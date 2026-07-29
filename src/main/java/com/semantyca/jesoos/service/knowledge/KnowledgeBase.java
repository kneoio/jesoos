package com.semantyca.jesoos.service.knowledge;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Platform-wide Mixpla knowledge base, backed by the OKF bundle in {@code resources/knowledge}.
 * Any agent in jesoos can query it; it carries no brand or chat scope.
 */
@ApplicationScoped
public class KnowledgeBase {

    public static final String BUNDLE_ROOT = "knowledge";
    public static final int DEFAULT_MAX_HITS = 6;

    private static final Logger LOG = Logger.getLogger(KnowledgeBase.class);
    private static final int MAX_SNIPPET = 1200;
    private static final int TITLE_WEIGHT = 5;
    private static final int TAG_WEIGHT = 4;
    private static final int DESCRIPTION_WEIGHT = 3;
    private static final int TYPE_WEIGHT = 2;

    private List<OkfConcept> concepts = List.of();

    public record Hit(OkfConcept concept, int score, String snippet) {}

    @PostConstruct
    void init() {
        concepts = OkfBundleLoader.load(BUNDLE_ROOT);
        LOG.infof("[knowledge] loaded %d concepts from '%s'", concepts.size(), BUNDLE_ROOT);
    }

    public boolean isEmpty() {
        return concepts.isEmpty();
    }

    /**
     * Searches the bundle, hiding concepts whose {@code audience} frontmatter does not include any
     * of the caller's audiences. Concepts without the key are visible to everyone.
     */
    public List<Hit> search(String query, int maxHits, Set<Audience> audiences) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) return List.of();

        Set<Audience> effective = (audiences == null || audiences.isEmpty())
                ? Set.of(Audience.USER)
                : audiences;

        List<Hit> hits = new ArrayList<>();
        for (OkfConcept concept : concepts) {
            if (!concept.isVisibleTo(effective)) continue;
            int score = score(concept, terms);
            if (score > 0) {
                hits.add(new Hit(concept, score, snippet(concept, terms)));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        return hits.size() > maxHits ? List.copyOf(hits.subList(0, maxHits)) : List.copyOf(hits);
    }

    private static List<String> terms(String query) {
        if (query == null) return List.of();
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(term -> term.replaceAll("[^a-z0-9_-]", ""))
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
    }

    private static int score(OkfConcept concept, List<String> terms) {
        String title = concept.title().toLowerCase(Locale.ROOT);
        String description = concept.description().toLowerCase(Locale.ROOT);
        String type = concept.type().toLowerCase(Locale.ROOT);
        String body = concept.body().toLowerCase(Locale.ROOT);
        List<String> tags = concept.tags().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();

        int score = 0;
        for (String term : terms) {
            if (title.contains(term)) score += TITLE_WEIGHT;
            if (tags.stream().anyMatch(tag -> tag.contains(term))) score += TAG_WEIGHT;
            if (description.contains(term)) score += DESCRIPTION_WEIGHT;
            if (type.contains(term)) score += TYPE_WEIGHT;
            if (body.contains(term)) score += 1;
        }
        return score;
    }

    /** Best-matching body section, so a hit carries the relevant part rather than the whole file. */
    private static String snippet(OkfConcept concept, List<String> terms) {
        String best = null;
        int bestScore = -1;
        for (String raw : concept.body().split("(?m)^# ")) {
            if (raw.isBlank()) continue;
            String section = concept.body().startsWith("# " + raw) ? "# " + raw : raw;
            String lower = section.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) {
                if (lower.contains(term)) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = section;
            }
        }
        String snippet = (best != null ? best : concept.body()).strip();
        return snippet.length() > MAX_SNIPPET ? snippet.substring(0, MAX_SNIPPET) + "…" : snippet;
    }
}
