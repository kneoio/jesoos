package com.semantyca.jesoos.service.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One concept document of an Open Knowledge Format (OKF v0.2) bundle: YAML frontmatter plus a
 * markdown body. {@code type} is the only key the spec requires; unknown keys are tolerated.
 */
public record OkfConcept(
        String path,
        String type,
        String title,
        String description,
        List<String> tags,
        List<String> audience,
        String body
) {

    /** A concept without an {@code audience} key is visible to every audience. */
    public boolean isVisibleTo(java.util.Set<Audience> audiences) {
        if (audience.isEmpty()) return true;
        return audience.stream()
                .map(Audience::of)
                .flatMap(java.util.Optional::stream)
                .anyMatch(audiences::contains);
    }

    private static final String DELIMITER = "---";

    /** Parses a concept file. Returns null when the document has no frontmatter block. */
    public static OkfConcept parse(String path, String raw) {
        if (raw == null || raw.isBlank()) return null;

        String normalized = raw.replace("\r\n", "\n").stripLeading();
        if (!normalized.startsWith(DELIMITER)) return null;

        int bodyStart = normalized.indexOf("\n" + DELIMITER, DELIMITER.length());
        if (bodyStart < 0) return null;

        String frontmatter = normalized.substring(DELIMITER.length(), bodyStart);
        String body = normalized.substring(bodyStart + DELIMITER.length() + 1).stripLeading();

        String type = null;
        String title = null;
        String description = null;
        List<String> tags = new ArrayList<>();
        List<String> audience = new ArrayList<>();
        String pendingListKey = null;

        for (String line : frontmatter.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            if (trimmed.startsWith("- ")) {
                String item = unquote(trimmed.substring(2).strip());
                if ("tags".equals(pendingListKey)) {
                    tags.add(item);
                } else if ("audience".equals(pendingListKey)) {
                    audience.add(item);
                }
                continue;
            }

            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;

            String key = trimmed.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colon + 1).strip();
            pendingListKey = value.isEmpty() ? key : null;

            switch (key) {
                case "type" -> type = unquote(value);
                case "title" -> title = unquote(value);
                case "description" -> description = unquote(value);
                case "tags" -> tags.addAll(parseInlineList(value));
                case "audience" -> audience.addAll(parseInlineList(value));
                default -> { }
            }
        }

        if (title == null || title.isBlank()) {
            title = fileNameOf(path);
        }
        return new OkfConcept(path, type != null ? type : "Concept", title,
                description != null ? description : "", List.copyOf(tags), List.copyOf(audience), body);
    }

    private static List<String> parseInlineList(String value) {
        List<String> items = new ArrayList<>();
        String inner = value.strip();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        } else if (inner.isEmpty()) {
            return items;
        }
        for (String part : inner.split(",")) {
            String item = unquote(part.strip());
            if (!item.isEmpty()) items.add(item);
        }
        return items;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String fileNameOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }
}
