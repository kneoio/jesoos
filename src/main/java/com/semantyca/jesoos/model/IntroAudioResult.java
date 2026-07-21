package com.semantyca.jesoos.model;

import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.cnst.TTSEngineType;

public record IntroAudioResult(
        String filePath,
        int durationSeconds,
        LanguageTag languageTag,
        boolean fallBacked,
        float gain,
        TTSEngineType engineType) {
}
