package com.semantyca.djinn.model.cnst;

import lombok.Getter;

@Getter
public enum LlmType {
    CLAUDE("claude"),
    OPENAI("openai"),
    GROQ("groq"),
    GROK("grok"),
    OPENROUTER("openrouter"),
    MOONSHOT("moonshot"),
    DEEPSEEK("deepseek");

    private final String value;

    LlmType(String value) {
        this.value = value;
    }

}
