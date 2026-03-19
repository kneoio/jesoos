package com.semantyca.jesoos.util;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.dto.queue.livestream.IntroKey;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.stream.IStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

import static com.semantyca.mixpla.dto.queue.livestream.IntroKey.INTRO_1;
import static com.semantyca.mixpla.dto.queue.livestream.IntroKey.INTRO_2;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_1;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_2;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_3;

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

    public static IntroKey getIntroKeyByIndex(int index) {
        return switch (index) {
            case 0 -> INTRO_1;
            case 1 -> INTRO_2;
            default -> throw new IllegalArgumentException("Unsupported intro index: " + index);
        };
    }

    public static SongKey getSongKeyByIndex(int index) {
        return switch (index) {
            case 0 -> SONG_1;
            case 1 -> SONG_2;
            case 2 -> SONG_3;
            default -> throw new IllegalArgumentException("Unsupported song index: " + index);
        };
    }


}
