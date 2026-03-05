package com.semantyca.djinn.dto;

import com.semantyca.mixpla.model.cnst.TTSEngineType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TtsDTO {
    private String primaryVoice;      // Voice ID for TTS
    private String secondaryVoice;    // For dialogue mode
    private String secondaryVoiceName;
    private TTSEngineType ttsEngineType;     // ELEVENLABS, AZURE
}
