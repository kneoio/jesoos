package com.semantyca.djinn.util;

import com.semantyca.core.model.ScriptVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScriptVariableExtractor {

    private static final Set<String> SYSTEM_VARIABLES = Set.of(
            "songTitle", "songArtist", "songDescription", "songGenres",
            "coPilotName", "coPilotVoiceId", "listeners",
            "djName", "djVoiceId", "profileName", "profileDescription",
            "stationBrand", "country", "language", "random",
            "perplexity", "weather", "news", "slugName", "timeContext"
    );

    private static final Pattern MARKER_PATTERN =
            Pattern.compile("//\\s*@var\\s+(\\w+)\\s*:\\s*(.+)$", Pattern.MULTILINE);

    private static final Pattern GS_USAGE_PATTERN =
            Pattern.compile("\\$\\{(\\w+)(?:\\.[\\w.]+)?}");

    private static final Pattern APPEND_USAGE_PATTERN =
            Pattern.compile("\\.append\\(\\s*(\\w+)\\s*\\)");

    private static final Pattern LOCAL_VAR_PATTERN =
            Pattern.compile("(?:def|int|String|boolean|double|float|long)\\s+(\\w+)\\s*=");

    private static final Set<String> GROOVY_KEYWORDS = Set.of(
            "sb", "def", "if", "else", "true", "false", "null", "new", "return", "it"
    );

    public static List<ScriptVariable> extract(String script) {
        return extract(script, SYSTEM_VARIABLES);
    }

    public static List<ScriptVariable> extract(String script, Set<String> systemVariables) {
        if (script == null || script.isBlank()) {
            return List.of();
        }

        Set<String> localVariables = new java.util.HashSet<>();
        Matcher localVarMatcher = LOCAL_VAR_PATTERN.matcher(script);
        while (localVarMatcher.find()) {
            localVariables.add(localVarMatcher.group(1));
        }

        Map<String, ScriptVariable> variables = new LinkedHashMap<>();

        Matcher markerMatcher = MARKER_PATTERN.matcher(script);
        while (markerMatcher.find()) {
            String name = markerMatcher.group(1);
            String desc = markerMatcher.group(2).trim();
            if (!systemVariables.contains(name) && !GROOVY_KEYWORDS.contains(name) && !localVariables.contains(name)) {
                variables.put(name, new ScriptVariable(name, desc));
            }
        }

        Matcher gsUsageMatcher = GS_USAGE_PATTERN.matcher(script);
        while (gsUsageMatcher.find()) {
            String name = gsUsageMatcher.group(1);
            if (!systemVariables.contains(name) && !GROOVY_KEYWORDS.contains(name) && !localVariables.contains(name) && !variables.containsKey(name)) {
                variables.put(name, new ScriptVariable(name, null));
            }
        }

        Matcher appendMatcher = APPEND_USAGE_PATTERN.matcher(script);
        while (appendMatcher.find()) {
            String name = appendMatcher.group(1);
            if (!systemVariables.contains(name) && !GROOVY_KEYWORDS.contains(name) && !localVariables.contains(name) && !variables.containsKey(name)) {
                variables.put(name, new ScriptVariable(name, null));
            }
        }

        return new ArrayList<>(variables.values());
    }

    public static Set<String> getSystemVariables() {
        return SYSTEM_VARIABLES;
    }
}
