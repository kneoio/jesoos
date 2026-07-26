package com.semantyca.jesoos.service.live.scripting;

import java.util.Map;
import java.util.Random;

public class DraftLineHelper {
    private final Map<String, Object> vars;
    private final Random random;

    public DraftLineHelper(Map<String, Object> vars, Random random) {
        this.vars = vars;
        this.random = random;
    }

    public String maybe(String key, String label, double probability) {
        Object value = vars != null ? vars.get(key) : null;
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return "";
        }
        if (random.nextDouble() >= probability) {
            return "";
        }
        return label + ": " + text + "\n";
    }

    /**
     * Like {@link #maybe} but takes the value directly instead of looking it up in {@code vars} —
     * for top-level template bindings (djName, stationBrand, songVibe, ...) that are not entries in
     * the station's userVariables. Empty/blank value -> "" (skipped), otherwise a "label: value" line
     * emitted with the given probability.
     */
    public String perhaps(String label, Object value, double probability) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return "";
        }
        if (random.nextDouble() >= probability) {
            return "";
        }
        return label + ": " + text + "\n";
    }
}
