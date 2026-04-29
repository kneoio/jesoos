package com.semantyca.jesoos.service.chat.llm.dto;

import java.util.Map;

public record LlmToolSpec(String name, String description, Map<String, Object> jsonSchema) {
}
