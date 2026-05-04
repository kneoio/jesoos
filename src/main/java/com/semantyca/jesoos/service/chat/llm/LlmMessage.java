package com.semantyca.jesoos.service.chat.llm;

public final class LlmMessage {

    public enum Role { USER, ASSISTANT }

    public enum Kind { TEXT, TOOL_USE, TOOL_RESULT }

    private final Role role;
    private final Kind kind;
    private final String text;
    private final LlmToolCall toolCall;
    private final String toolResultId;
    private final String toolResultContent;

    private LlmMessage(Role role, Kind kind, String text, LlmToolCall toolCall,
                       String toolResultId, String toolResultContent) {
        this.role = role;
        this.kind = kind;
        this.text = text;
        this.toolCall = toolCall;
        this.toolResultId = toolResultId;
        this.toolResultContent = toolResultContent;
    }

    public static LlmMessage text(Role role, String content) {
        return new LlmMessage(role, Kind.TEXT, content, null, null, null);
    }

    public static LlmMessage toolUse(LlmToolCall toolCall) {
        return new LlmMessage(Role.ASSISTANT, Kind.TOOL_USE, null, toolCall, null, null);
    }

    public static LlmMessage toolResult(String toolUseId, String content) {
        return new LlmMessage(Role.USER, Kind.TOOL_RESULT, null, null, toolUseId, content);
    }

    public Role role() { return role; }
    public Kind kind() { return kind; }
    public String text() { return text; }
    public LlmToolCall toolCall() { return toolCall; }
    public String toolResultId() { return toolResultId; }
    public String toolResultContent() { return toolResultContent; }
}
