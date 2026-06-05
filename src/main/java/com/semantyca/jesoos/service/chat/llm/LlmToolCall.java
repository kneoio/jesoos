package com.semantyca.jesoos.service.chat.llm;

import java.io.Serializable;
import java.util.Map;

public record LlmToolCall(String id, String name, Map<String, Object> input) implements Serializable {}
