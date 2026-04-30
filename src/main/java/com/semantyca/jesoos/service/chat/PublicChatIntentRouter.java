package com.semantyca.jesoos.service.chat;

import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.service.chat.llm.AnthropicChatLlmClient;
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

    private AnthropicChatLlmClient llmClient;

    PublicChatIntentRouter() {}

    PublicChatIntentRouter(OtsSessionManager otsSessionManager, AnthropicChatLlmClient llmClient) {
        this.otsSessionManager = otsSessionManager;
        this.llmClient = llmClient;
        this.config = null;
    }

    @PostConstruct
    void init() {
        llmClient = new AnthropicChatLlmClient(config.getAnthropicApiKey());
        LOGGER.info("[router] classifier provider=anthropic");
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
        MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5_20251001)
                    .maxTokens(120)
                    .system("""
                            Classify the user message intent.
                            START_OTS: user explicitly wants to start or continue a one-time radio stream.
                            NORMAL_CHAT: anything else.
                            
                            Reply with ONLY a single JSON object, no markdown and no extra text.
                            Required JSON fields:
                            - intent: "START_OTS" or "NORMAL_CHAT"
                            - confidence: number from 0 to 1
                            - reason: short string
                            
                            Examples:
                            User: "start one-time stream for my brand"
                            {"intent":"START_OTS","confidence":0.97,"reason":"explicitly asks to start one-time stream"}
                            
                            User: "can we do ots now?"
                            {"intent":"START_OTS","confidence":0.92,"reason":"explicit request to do OTS"}
                            """)
                .addUserMessage(userMessage)
                .build();

        return Uni.createFrom().completionStage(() -> llmClient.createMessage(params)).map(response -> {
            String raw = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(b -> b.asText().text())
                    .findFirst()
                    .orElse("")
                    .trim();

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
            if ("START_OTS".equals(payload.intent())) {
                return IntentDecision.llm(ChatIntent.START_OTS, confidence, payload.reason());
            }
            if ("NORMAL_CHAT".equals(payload.intent())) {
                return IntentDecision.llm(ChatIntent.NORMAL_CHAT, confidence, payload.reason());
            }

            throw new IllegalStateException("unknown LLM intent in JSON output: " + raw);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private record LlmClassifierPayload(String intent, Double confidence, String reason) {}
}
