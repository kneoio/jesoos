package com.semantyca.jesoos.service.chat.llm;

import java.util.Optional;

public final class LlmResponse {

    private final String text;
    private final LlmToolCall toolCall;

    public LlmResponse(String text, LlmToolCall toolCall) {
        this.text = text;
        this.toolCall = toolCall;
    }

    public String text() { return text; }

    public Optional<LlmToolCall> toolCall() { return Optional.ofNullable(toolCall); }
}
