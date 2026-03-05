package com.semantyca.djinn.dto;

import com.semantyca.mixpla.model.cnst.StreamType;
import lombok.Data;

import java.util.List;

@Data
public class LiveRadioStationDTO {
    private String name;
    private String slugName;
    private String streamStatus;
    private StreamType streamType;        // RADIO or ONE_TIME_STREAM
    private String djName;
    private String languageTag;
    private TtsDTO tts;
    private List<SongPromptDTO> prompts;
    private String info;
}
