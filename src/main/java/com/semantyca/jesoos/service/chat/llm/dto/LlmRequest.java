package com.semantyca.jesoos.service.chat.llm.dto;

import java.util.List;

public record LlmRequest(
        String systemPrompt,
        List<LlmMessage> messages,
        List<LlmToolSpec> tools,
        Long maxTokens,
        Double temperature,
        Double topP,
        boolean stream
) {
}
