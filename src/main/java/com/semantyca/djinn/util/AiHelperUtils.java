package com.semantyca.djinn.util;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.stream.IStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

public final class AiHelperUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperUtils.class);

    private AiHelperUtils() {
    }

    public static boolean shouldPlayJingle(double talkativity) {
        double jingleProbability = 1.0 - talkativity;
        double randomValue = new Random().nextDouble();
        return randomValue < jingleProbability;
    }

    public static LanguageTag selectLanguageByWeight(AiAgent agent) {
        List<LanguagePreference> preferences = agent.getPreferredLang();
        if (preferences == null || preferences.isEmpty()) {
            LOGGER.warn("Agent '{}' has no language preferences, defaulting to English", agent.getName());
            return LanguageTag.EN_GB;
        }

        if (preferences.size() == 1) {
            return preferences.getFirst().getLanguageTag();
        }

        double totalWeight = preferences.stream()
                .mapToDouble(LanguagePreference::getWeight)
                .sum();

        if (totalWeight <= 0) {
            LOGGER.warn("Agent '{}' has invalid weights (total <= 0), using first language", agent.getName());
            return preferences.getFirst().getLanguageTag();
        }

        double randomValue = new Random().nextDouble() * totalWeight;
        double cumulativeWeight = 0;
        for (LanguagePreference pref : preferences) {
            cumulativeWeight += pref.getWeight();
            if (randomValue <= cumulativeWeight) {
                return pref.getLanguageTag();
            }
        }

        return preferences.getFirst().getLanguageTag();
    }

    public static String resolvePrimaryVoiceId(IStream currentStream, AiAgent agent) {
        AiOverriding overriding = currentStream.getAiOverriding();
        if (overriding != null && overriding.getPrimaryVoice() != null) {
            return overriding.getPrimaryVoice();
        }
        return agent.getTtsSetting().getDj().getId();
    }
}
