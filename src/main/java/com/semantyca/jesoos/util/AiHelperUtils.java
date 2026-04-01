package com.semantyca.jesoos.util;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.dto.queue.livestream.IntroKey;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import org.jboss.logging.Logger;


import java.util.List;
import java.util.Random;

import static com.semantyca.mixpla.dto.queue.livestream.IntroKey.INTRO_1;
import static com.semantyca.mixpla.dto.queue.livestream.IntroKey.INTRO_2;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_1;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_2;
import static com.semantyca.mixpla.dto.queue.livestream.SongKey.SONG_3;

public final class AiHelperUtils {
    private static final Logger LOGGER = Logger.getLogger(AiHelperUtils.class);

    public static LanguageTag selectLanguageByWeight(AiAgent agent) {
        List<LanguagePreference> preferences = agent.getPreferredLang();
        if (preferences == null || preferences.isEmpty()) {
            LOGGER.warnf("Agent %s has no language preferences, defaulting to English", agent.getName());
            return LanguageTag.EN_GB;
        }

        if (preferences.size() == 1) {
            return preferences.getFirst().getLanguageTag();
        }

        double totalWeight = preferences.stream()
                .mapToDouble(LanguagePreference::getWeight)
                .sum();

        if (totalWeight <= 0) {
            LOGGER.warnf("Agent %s has invalid weights (total <= 0), using first language", agent.getName());
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
