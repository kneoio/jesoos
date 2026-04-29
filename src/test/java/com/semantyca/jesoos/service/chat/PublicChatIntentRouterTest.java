package com.semantyca.jesoos.service.chat;

import com.semantyca.jesoos.service.chat.ots.OtsSessionData;
import com.semantyca.jesoos.service.chat.ots.OtsSessionManager;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PublicChatIntentRouter.
 *
 * The package-private constructor and package-private classifyWithLlm() allow
 * test-controlled subclasses to exercise every branch without hitting a real API.
 */
class PublicChatIntentRouterTest {

    private OtsSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new OtsSessionManager();
    }

    // ---- 1. Deterministic: active OTS session → START_OTS ----

    @Test
    void decide_returnsStartOts_deterministically_whenSessionIsActive() {
        String connectionId = "conn-1";
        sessionManager.start(connectionId, fakeSession());

        // null client — proves LLM is never called on the deterministic path
        PublicChatIntentRouter router = new PublicChatIntentRouter(sessionManager, null);

        IntentDecision decision = router.decide(connectionId, "hello").await().indefinitely();

        assertEquals(ChatIntent.START_OTS, decision.intent());
        assertEquals(IntentDecision.DecisionSource.DETERMINISTIC, decision.source());
        assertEquals(1.0, decision.confidence());
        assertTrue(decision.reason().contains("active OTS session"));
    }

    @Test
    void decide_doesNotCallLlm_whenSessionIsActive() {
        String connectionId = "conn-null-client";
        sessionManager.start(connectionId, fakeSession());

        // If LLM were invoked on a null client it would NPE — no exception means it was skipped.
        PublicChatIntentRouter router = new PublicChatIntentRouter(sessionManager, null);

        assertDoesNotThrow(() ->
                router.decide(connectionId, "any message").await().indefinitely()
        );
    }

    // ---- 2. UNKNOWN → LLM classification path ----

    @Test
    void decide_callsLlm_whenNoActiveSession_andLlmReturnsNormalChat() {
        PublicChatIntentRouter router = routerWithLlmResult(
                IntentDecision.llm(ChatIntent.NORMAL_CHAT, 0.9, "LLM classified as NORMAL_CHAT"));

        IntentDecision decision = router.decide("conn-new", "What's playing?").await().indefinitely();

        assertEquals(ChatIntent.NORMAL_CHAT, decision.intent());
        assertEquals(IntentDecision.DecisionSource.LLM, decision.source());
    }

    @Test
    void decide_callsLlm_whenNoActiveSession_andLlmReturnsStartOts() {
        PublicChatIntentRouter router = routerWithLlmResult(
                IntentDecision.llm(ChatIntent.START_OTS, 0.9, "LLM classified as START_OTS"));

        IntentDecision decision = router.decide("conn-new", "start my stream").await().indefinitely();

        assertEquals(ChatIntent.START_OTS, decision.intent());
        assertEquals(IntentDecision.DecisionSource.LLM, decision.source());
    }

    // ---- 3. LLM invalid output → NORMAL_CHAT fallback ----

    @Test
    void decide_fallsBackToNormalChat_whenLlmReturnsFallback() {
        PublicChatIntentRouter router = routerWithLlmResult(
                IntentDecision.fallback("unexpected LLM output: BLAH"));

        IntentDecision decision = router.decide("conn-new", "hello").await().indefinitely();

        assertEquals(ChatIntent.NORMAL_CHAT, decision.intent());
        assertEquals(IntentDecision.DecisionSource.FALLBACK, decision.source());
    }

    @Test
    void decide_fallsBackToNormalChat_whenLlmThrows() {
        PublicChatIntentRouter router = routerWithLlmFailure(new RuntimeException("simulated LLM timeout"));

        IntentDecision decision = router.decide("conn-new", "hello").await().indefinitely();

        assertEquals(ChatIntent.NORMAL_CHAT, decision.intent());
        assertEquals(IntentDecision.DecisionSource.FALLBACK, decision.source());
        assertTrue(decision.reason().contains("LLM error"));
    }

    // ---- 4. No legacy inline isActive() check: source is LLM when no session ----

    @Test
    void noActiveSession_sourceMustBeLlmOrFallback_provingRouterIsGate() {
        // With no session the deterministic path yields UNKNOWN, so the router must delegate to LLM.
        // A legacy raw isActive() check would have returned a boolean, not a decision object.
        PublicChatIntentRouter router = routerWithLlmResult(
                IntentDecision.llm(ChatIntent.NORMAL_CHAT, 0.9, "classified"));

        IntentDecision decision = router.decide("conn-no-session", "anything").await().indefinitely();

        assertNotEquals(IntentDecision.DecisionSource.DETERMINISTIC, decision.source(),
                "No session → must not use deterministic path");
    }

    // ---- helpers ----

    private static OtsSessionData fakeSession() {
        return new OtsSessionData("brand", UUID.randomUUID(), "script", List.of(), Map.of());
    }

    /** Router whose classifyWithLlm() always returns the given fixed decision. */
    private PublicChatIntentRouter routerWithLlmResult(IntentDecision result) {
        return new PublicChatIntentRouter(sessionManager, null) {
            @Override
            Uni<IntentDecision> classifyWithLlm(String userMessage) {
                return Uni.createFrom().item(result);
            }
        };
    }

    /** Router whose classifyWithLlm() always fails with the given error. */
    private PublicChatIntentRouter routerWithLlmFailure(Throwable failure) {
        return new PublicChatIntentRouter(sessionManager, null) {
            @Override
            Uni<IntentDecision> classifyWithLlm(String userMessage) {
                return Uni.createFrom().failure(failure);
            }
        };
    }
}
