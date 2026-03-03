package com.semantyca.djinn.model.cnst;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TTSEngineType {
    ELEVENLABS("elevenlabs"),
    MODELSLAB("modelslab"),
    GOOGLE("google");

    private final String value;

    TTSEngineType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TTSEngineType fromValue(String value) {
        for (TTSEngineType type : TTSEngineType.values()) {
            if (type.name().equalsIgnoreCase(value) || type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown TTSEngineType: " + value);
    }
}
