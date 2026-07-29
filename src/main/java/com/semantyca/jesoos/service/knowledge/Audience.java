package com.semantyca.jesoos.service.knowledge;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who an agent is talking to, derived from the labels on the caller's Listener row.
 * {@link #USER} is always present; the rest are additive.
 *
 * <p>Labels are read-only from chat: assignment of {@link #OWNER} / {@link #DEVELOPER} happens in
 * datanest/admin, never through a tool call.
 */
public enum Audience {

    USER("user"),
    ARTIST("artist"),
    OWNER("owner"),
    DEVELOPER("developer");

    /** Label identifiers in the shared {@code __labels} table that map onto an audience. */
    public static final String LABEL_ARTIST = "artist";
    public static final String LABEL_OWNER = "owner";
    public static final String LABEL_DEVELOPER = "developer";

    private final String identifier;

    Audience(String identifier) {
        this.identifier = identifier;
    }

    public String identifier() {
        return identifier;
    }

    /** Resolved label identifiers of a Listener to the audiences they grant. */
    public static Set<Audience> fromLabels(Collection<String> labelIdentifiers) {
        Set<Audience> audiences = EnumSet.of(USER);
        if (labelIdentifiers == null) return audiences;
        for (String label : labelIdentifiers) {
            if (label == null) continue;
            switch (label.toLowerCase(Locale.ROOT)) {
                case LABEL_ARTIST -> audiences.add(ARTIST);
                case LABEL_OWNER -> audiences.add(OWNER);
                case LABEL_DEVELOPER -> audiences.add(DEVELOPER);
                default -> { }
            }
        }
        return audiences;
    }

    /** Most specific audience, used for tone and answer depth. Precedence: developer, owner, artist, user. */
    public static Audience primary(Set<Audience> audiences) {
        if (audiences == null) return USER;
        if (audiences.contains(DEVELOPER)) return DEVELOPER;
        if (audiences.contains(OWNER)) return OWNER;
        if (audiences.contains(ARTIST)) return ARTIST;
        return USER;
    }

    public static String join(Set<Audience> audiences) {
        if (audiences == null || audiences.isEmpty()) return USER.identifier;
        return audiences.stream().map(Audience::identifier).sorted().reduce((a, b) -> a + "," + b).orElse(USER.identifier);
    }

    public static Set<Audience> parse(String joined) {
        Set<Audience> audiences = EnumSet.of(USER);
        if (joined == null || joined.isBlank()) return audiences;
        for (String part : joined.split(",")) {
            of(part).ifPresent(audiences::add);
        }
        return audiences;
    }

    public static java.util.Optional<Audience> of(String value) {
        if (value == null) return java.util.Optional.empty();
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return List.of(values()).stream().filter(a -> a.identifier.equals(normalized)).findFirst();
    }
}
