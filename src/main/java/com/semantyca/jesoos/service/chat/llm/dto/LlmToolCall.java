package com.semantyca.jesoos.service.chat.llm.dto;

import java.util.Map;

public record LlmToolCall(String id, String name, Map<String, Object> arguments) {
}
