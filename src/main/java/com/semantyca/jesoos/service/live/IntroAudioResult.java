package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.cnst.LanguageTag;

public record IntroAudioResult(
        String filePath,
        int durationSeconds,
        LanguageTag languageTag,
        boolean fallBacked,
        float gain) {
}
