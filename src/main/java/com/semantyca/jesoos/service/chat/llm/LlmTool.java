package com.semantyca.jesoos.service.chat.llm;

import java.util.List;
import java.util.Map;

public record LlmTool(String name, String description, Map<String, Object> properties, List<String> required) {

    public static LlmTool of(String name, String description, Map<String, Object> properties, List<String> required) {
        return new LlmTool(name, description, properties, required);
    }

    public static LlmTool of(String name, String description, Map<String, Object> properties) {
        return new LlmTool(name, description, properties, List.of());
    }
}
