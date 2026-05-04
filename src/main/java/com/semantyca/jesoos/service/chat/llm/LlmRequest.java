package com.semantyca.jesoos.service.chat.llm;

import java.util.ArrayList;
import java.util.List;

public final class LlmRequest {

    private final String model;
    private final String system;
    private final List<LlmMessage> messages;
    private final long maxTokens;
    private final List<LlmTool> tools;

    private LlmRequest(Builder builder) {
        this.model = builder.model;
        this.system = builder.system;
        this.messages = List.copyOf(builder.messages);
        this.maxTokens = builder.maxTokens;
        this.tools = List.copyOf(builder.tools);
    }

    public String model() { return model; }
    public String system() { return system; }
    public List<LlmMessage> messages() { return messages; }
    public long maxTokens() { return maxTokens; }
    public List<LlmTool> tools() { return tools; }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.model = this.model;
        b.system = this.system;
        b.messages.addAll(this.messages);
        b.maxTokens = this.maxTokens;
        b.tools.addAll(this.tools);
        return b;
    }

    public static final class Builder {
        private String model;
        private String system;
        private final List<LlmMessage> messages = new ArrayList<>();
        private long maxTokens = 1024L;
        private final List<LlmTool> tools = new ArrayList<>();

        public Builder model(String model) { this.model = model; return this; }
        public Builder system(String system) { this.system = system; return this; }
        public Builder messages(List<LlmMessage> messages) { this.messages.addAll(messages); return this; }
        public Builder addMessage(LlmMessage message) { this.messages.add(message); return this; }
        public Builder maxTokens(long maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder tools(List<LlmTool> tools) { this.tools.addAll(tools); return this; }
        public Builder addTool(LlmTool tool) { this.tools.add(tool); return this; }

        public LlmRequest build() { return new LlmRequest(this); }
    }
}
