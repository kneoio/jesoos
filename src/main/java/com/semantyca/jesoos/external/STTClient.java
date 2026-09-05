package com.semantyca.jesoos.external;

import com.semantyca.core.model.cnst.LanguageTag;
import io.smallrye.mutiny.Uni;

import java.nio.file.Path;
import java.util.List;

public interface STTClient {

    Uni<SttResult> transcribe(Path audioFile, List<LanguageTag> languageHints);

    default Uni<SttResult> transcribe(Path audioFile) {
        return transcribe(audioFile, List.of());
    }
}
