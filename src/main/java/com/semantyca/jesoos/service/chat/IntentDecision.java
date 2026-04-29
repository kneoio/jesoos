package com.semantyca.jesoos.service.chat;

public record IntentDecision(
        ChatIntent intent,
        double confidence,
        String reason,
        DecisionSource source
) {
    public enum DecisionSource { DETERMINISTIC, LLM, FALLBACK }

    public static IntentDecision deterministic(ChatIntent intent, String reason) {
        return new IntentDecision(intent, 1.0, reason, DecisionSource.DETERMINISTIC);
    }

    public static IntentDecision llm(ChatIntent intent, double confidence, String reason) {
        return new IntentDecision(intent, confidence, reason, DecisionSource.LLM);
    }

    public static IntentDecision fallback(String reason) {
        return new IntentDecision(ChatIntent.NORMAL_CHAT, 0.0, reason, DecisionSource.FALLBACK);
    }
}
