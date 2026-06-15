package com.semantyca.jesoos.service.chat.llm;

import java.util.ArrayList;
import java.util.List;

public final class LlmRequest {

    private final String model;
    private final String systemStable;
    private final String systemVolatile;
    private final boolean cacheSystem;
    private final List<LlmMessage> messages;
    private final long maxTokens;
    private final List<LlmTool> tools;

    private LlmRequest(Builder builder) {
        this.model = builder.model;
        this.systemStable = builder.systemStable;
        this.systemVolatile = builder.systemVolatile;
        this.cacheSystem = builder.cacheSystem;
        this.messages = List.copyOf(builder.messages);
        this.maxTokens = builder.maxTokens;
        this.tools = List.copyOf(builder.tools);
    }

    public String model() { return model; }

    /** Static, cacheable portion of the system prompt. */
    public String systemStable() { return systemStable; }

    /** Per-request portion of the system prompt that must not be cached (may be null). */
    public String systemVolatile() { return systemVolatile; }

    /** Whether the stable system portion should be marked for explicit provider caching. */
    public boolean cacheSystem() { return cacheSystem; }

    /** Convenience: stable + volatile combined, for callers/providers that use a single system string. */
    public String system() {
        if (systemStable == null) return systemVolatile;
        if (systemVolatile == null || systemVolatile.isBlank()) return systemStable;
        return systemStable + "\n" + systemVolatile;
    }

    public List<LlmMessage> messages() { return messages; }
    public long maxTokens() { return maxTokens; }
    public List<LlmTool> tools() { return tools; }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.model = this.model;
        b.systemStable = this.systemStable;
        b.systemVolatile = this.systemVolatile;
        b.cacheSystem = this.cacheSystem;
        b.messages.addAll(this.messages);
        b.maxTokens = this.maxTokens;
        b.tools.addAll(this.tools);
        return b;
    }

    public static final class Builder {
        private String model;
        private String systemStable;
        private String systemVolatile;
        private boolean cacheSystem = false;
        private final List<LlmMessage> messages = new ArrayList<>();
        private long maxTokens = 1024L;
        private final List<LlmTool> tools = new ArrayList<>();

        public Builder model(String model) { this.model = model; return this; }
        /** Sets the stable (cacheable) system text. */
        public Builder system(String system) { this.systemStable = system; return this; }
        public Builder systemStable(String systemStable) { this.systemStable = systemStable; return this; }
        public Builder systemVolatile(String systemVolatile) { this.systemVolatile = systemVolatile; return this; }
        public Builder cacheSystem(boolean cacheSystem) { this.cacheSystem = cacheSystem; return this; }
        public Builder messages(List<LlmMessage> messages) { this.messages.addAll(messages); return this; }
        public Builder addMessage(LlmMessage message) { this.messages.add(message); return this; }
        public Builder maxTokens(long maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder tools(List<LlmTool> tools) { this.tools.addAll(tools); return this; }
        public Builder addTool(LlmTool tool) { this.tools.add(tool); return this; }

        public LlmRequest build() { return new LlmRequest(this); }
    }
}
