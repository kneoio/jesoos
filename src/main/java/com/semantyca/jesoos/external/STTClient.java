package com.semantyca.jesoos.external;

import com.semantyca.core.model.cnst.LanguageTag;
import io.smallrye.mutiny.Uni;

import java.nio.file.Path;

public interface STTClient {
    Uni<SttResult> transcribe(Path audioFile, LanguageTag language);
}
