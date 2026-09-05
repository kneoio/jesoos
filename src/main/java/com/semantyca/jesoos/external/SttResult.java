package com.semantyca.jesoos.external;

import com.semantyca.mixpla.model.cnst.STTEngineType;

public record SttResult(boolean ok, String transcript, float confidence, String languageCode,
                        STTEngineType engineType, String error) {

    public static SttResult success(String transcript, float confidence, String languageCode) {
        return new SttResult(true, transcript, confidence, languageCode, STTEngineType.GOOGLE, null);
    }

    public static SttResult failure(String error) {
        return new SttResult(false, "", 0f, null, STTEngineType.GOOGLE, error);
    }

    public boolean hasText() {
        return ok && transcript != null && !transcript.isBlank();
    }
}
