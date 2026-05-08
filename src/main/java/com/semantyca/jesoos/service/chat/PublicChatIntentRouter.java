package com.semantyca.jesoos.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.service.chat.llm.ChatLlmClient;
import com.semantyca.jesoos.service.chat.llm.LlmProviderAdapter;
import com.semantyca.jesoos.service.chat.llm.LlmProviderRegistry;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmUseCase;
import com.semantyca.jesoos.service.chat.ots.OtsSessionManager;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Deterministic-first cascade router for public chat intent classification.
 *
 * Cascade order:
 *  1. Deterministic rules (no LLM cost, always wins when applicable)
 *  2. LLM classifier (Haiku, strict enum output) when deterministic result is UNKNOWN
 * No provider fallback is applied.
 */
@ApplicationScoped
public class PublicChatIntentRouter {

    private static final Logger LOGGER = Logger.getLogger(PublicChatIntentRouter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    OtsSessionManager otsSessionManager;

    @Inject
    JesoosConfig config;

    private ChatLlmClient llmClient;
    private LlmProviderAdapter llmProviderAdapter;

    PublicChatIntentRouter() {}

    PublicChatIntentRouter(OtsSessionManager otsSessionManager, ChatLlmClient llmClient) {
        this.otsSessionManager = otsSessionManager;
        this.llmClient = llmClient;
        this.config = null;
    }

    @PostConstruct
    void init() {
        String provider = config.getLlmProvider();
        llmProviderAdapter = LlmProviderRegistry.resolve(provider);
        llmClient = llmProviderAdapter.createClient(config);
        LOGGER.infof("[router] classifier provider=%s", provider);
    }

    /**
     * Decide intent for one user turn.
     * UNKNOWN is resolved internally via LLM classification.
     */
    public Uni<IntentDecision> decide(String connectionId, String userMessage) {
        IntentDecision deterministic = applyDeterministicRules(connectionId);
        if (deterministic.intent() != ChatIntent.UNKNOWN) {
            LOGGER.infof("[router] connectionId=%s intent=%s source=%s reason=%s",
                    connectionId, deterministic.intent(), deterministic.source(), deterministic.reason());
            return Uni.createFrom().item(deterministic);
        }

        return classifyWithLlm(userMessage)
                .invoke(decision -> LOGGER.infof("[router] connectionId=%s intent=%s source=%s reason=%s",
                        connectionId, decision.intent(), decision.source(), decision.reason()));
    }

    /**
     * Conservative deterministic rules — only commit when the signal is unambiguous.
     * Returns UNKNOWN to hand off to the LLM when no rule matches.
     */
    private IntentDecision applyDeterministicRules(String connectionId) {
        if (otsSessionManager.isActive(connectionId)) {
            return IntentDecision.deterministic(ChatIntent.START_OTS, "active OTS session");
        }
        return new IntentDecision(ChatIntent.UNKNOWN, 0.0, "no deterministic signal", IntentDecision.DecisionSource.DETERMINISTIC);
    }

    Uni<IntentDecision> classifyWithLlm(String userMessage) {
        LlmRequest request = LlmRequest.builder()
                .model(resolveClassifierModel())
                .maxTokens(120)
                .system("""
                        Classify the user message intent.

                        START_OTS: user explicitly wants to create a personalised one-time radio stream (OTS) for a special occasion such as a birthday party, workout session, celebration, event, or gathering. They typically know what OTS means and ask for it directly.
                        NORMAL_CHAT: anything else — browsing, asking questions, requesting a song, replying yes/no/ok to the bot, small talk, etc.

                        When in doubt, choose NORMAL_CHAT. Only return START_OTS when the intent is unambiguous.

                        Reply with ONLY a single JSON object, no markdown and no extra text.
                        Required JSON fields:
                        - intent: "START_OTS" or "NORMAL_CHAT"
                        - confidence: number from 0 to 1
                        - reason: short string

                        Examples:
                        User: "I want to create an OTS for my birthday party"
                        {"intent":"START_OTS","confidence":0.98,"reason":"explicit OTS request for birthday party"}

                        User: "can we do ots now?"
                        {"intent":"START_OTS","confidence":0.95,"reason":"direct OTS request using the term"}

                        User: "I need a custom stream for my gym workout"
                        {"intent":"START_OTS","confidence":0.91,"reason":"requests personalised stream for specific occasion"}

                        User: "start one-time stream for my brand celebration"
                        {"intent":"START_OTS","confidence":0.97,"reason":"explicit one-time stream request for an event"}

                        User: "yes"
                        {"intent":"NORMAL_CHAT","confidence":0.99,"reason":"single-word reply, likely confirming something in conversation"}

                        User: "ok"
                        {"intent":"NORMAL_CHAT","confidence":0.99,"reason":"acknowledgement, not an OTS request"}

                        User: "can I order a song?"
                        {"intent":"NORMAL_CHAT","confidence":0.99,"reason":"song request, not OTS"}

                        User: "what's playing?"
                        {"intent":"NORMAL_CHAT","confidence":0.99,"reason":"status question"}

                        User: "queue it"
                        {"intent":"NORMAL_CHAT","confidence":0.99,"reason":"queuing a song, not OTS"}
                        """)
                .addMessage(com.semantyca.jesoos.service.chat.llm.LlmMessage.text(
                        com.semantyca.jesoos.service.chat.llm.LlmMessage.Role.USER, userMessage))
                .build();

        return Uni.createFrom().completionStage(() -> llmClient.createMessage(request)).map(response -> {
            String raw = response.text().trim();

            if (raw.startsWith("```")) {
                raw = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
            }
            final LlmClassifierPayload payload;
            try {
                payload = OBJECT_MAPPER.readValue(raw, LlmClassifierPayload.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("invalid LLM JSON output: " + raw, e);
            }

            if (payload.intent() == null || payload.reason() == null || payload.reason().isBlank()) {
                throw new IllegalStateException("incomplete LLM JSON output: " + raw);
            }

            double confidence = payload.confidence() == null ? 0.0 : Math.max(0.0, Math.min(1.0, payload.confidence()));
            if ("START_OTS".equals(payload.intent()) && confidence >= 0.85) {
                return IntentDecision.llm(ChatIntent.START_OTS, confidence, payload.reason());
            }
            if ("NORMAL_CHAT".equals(payload.intent()) || confidence < 0.85) {
                return IntentDecision.llm(ChatIntent.NORMAL_CHAT, confidence, payload.reason());
            }

            throw new IllegalStateException("unknown LLM intent in JSON output: " + raw);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private String resolveClassifierModel() {
        if (llmProviderAdapter == null) {
            return com.semantyca.jesoos.service.chat.llm.LlmModels.CLAUDE_HAIKU_4_5;
        }
        return llmProviderAdapter.modelFor(LlmUseCase.INTENT_CLASSIFIER);
    }

    private record LlmClassifierPayload(String intent, Double confidence, String reason) {}
}
