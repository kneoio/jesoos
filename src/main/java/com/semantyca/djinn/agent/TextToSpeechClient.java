package com.semantyca.djinn.agent;

import com.semantyca.core.model.cnst.LanguageTag;
import io.smallrye.mutiny.Uni;

public interface TextToSpeechClient {
    Uni<byte[]> textToSpeech(String text, String voiceId, String modelId, LanguageTag languageTag);
}
