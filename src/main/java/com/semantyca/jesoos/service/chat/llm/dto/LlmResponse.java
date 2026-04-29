package com.semantyca.jesoos.service.chat.llm.dto;

import java.util.List;
import java.util.Map;

public record LlmResponse(
        String text,
        List<LlmToolCall> toolCalls,
        String model,
        Map<String, Object> usage
) {
}
