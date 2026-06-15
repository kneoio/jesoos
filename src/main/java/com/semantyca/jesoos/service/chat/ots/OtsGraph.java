package com.semantyca.jesoos.service.chat.ots;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.service.OneTimeStreamService;
import com.semantyca.jesoos.service.chat.llm.BrandLlmProviderResolver;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmUseCase;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

@ApplicationScoped
public class OtsGraph {

    private static final Logger LOGGER = Logger.getLogger(OtsGraph.class);

    @Inject
    OtsSessionManager sessionManager;

    @Inject
    OneTimeStreamService oneTimeStreamService;

    @Inject
    JesoosConfig config;

    @Inject
    BrandLlmProviderResolver llmProviderResolver;

    private CompiledGraph<OtsState> compiledGraph;
    private String otsPromptTemplate;

    @PostConstruct
    void init() {
        try {
            otsPromptTemplate = loadResource("prompts/otsPrompt.hbs");

            compiledGraph = new StateGraph<>(OtsState::new)
                    .addNode("collectTurn",   this::collectTurnNode)
                    .addNode("checkMissing",  this::checkMissingNode)
                    .addNode("askQuestion",   this::askQuestionNode)
                    .addNode("startStream",   this::startStreamNode)
                    .addEdge(START, "collectTurn")
                    .addEdge("collectTurn", "checkMissing")
                    .addConditionalEdges("checkMissing",
                            state -> CompletableFuture.completedFuture("start".equals(state.action()) ? "startStream" : "askQuestion"),
                            Map.of("askQuestion", "askQuestion", "startStream", "startStream"))
                    .addEdge("askQuestion", END)
                    .addEdge("startStream", END)
                    .compile();

            LOGGER.info("[OtsGraph] compiled successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile OtsGraph", e);
        }
    }

    // --- Graph nodes ---

    private CompletableFuture<Map<String, Object>> collectTurnNode(OtsState state) {
        String pendingVar = state.pendingVarName();
        if (pendingVar == null || pendingVar.isBlank() || state.userMessage().isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Map<String, String> updated = new HashMap<>(state.collectedVars());
        updated.put(pendingVar, state.userMessage().trim());
        LOGGER.infof("[OtsGraph] collected var=%s value=%s", pendingVar, state.userMessage().trim());
        return CompletableFuture.completedFuture(Map.of(OtsState.COLLECTED_VARS, updated));
    }

    private CompletableFuture<Map<String, Object>> checkMissingNode(OtsState state) {
        List<String> required = state.requiredVarNames();
        Map<String, String> collected = state.collectedVars();

        String nextMissing = required.stream()
                .filter(v -> !collected.containsKey(v) || collected.get(v).isBlank())
                .findFirst()
                .orElse(null);

        if (nextMissing == null) {
            LOGGER.info("[OtsGraph] all vars collected → startStream");
            return CompletableFuture.completedFuture(Map.of(OtsState.ACTION, "start"));
        }
        LOGGER.infof("[OtsGraph] next missing var=%s", nextMissing);
        return CompletableFuture.completedFuture(Map.of(
                OtsState.ACTION, "ask",
                OtsState.NEXT_PENDING_VAR, nextMissing
        ));
    }

    private CompletableFuture<Map<String, Object>> askQuestionNode(OtsState state) {
        String varName = state.nextPendingVar();
        String description = state.varDescriptions().getOrDefault(varName, varName);
        String scriptName = state.scriptName();
        String brandSlug = state.brandSlug();

        String collected = state.collectedVars().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        String systemPrompt = otsPromptTemplate
                .replace("{{djName}}", state.djName())
                .replace("{{scriptName}}", scriptName)
                .replace("{{collectedVars}}", collected.isEmpty() ? "none yet" : collected)
                .replace("{{pendingVarName}}", varName)
                .replace("{{pendingVarDescription}}", description);

        LlmRequest request = LlmRequest.builder()
                .model(llmProviderResolver.modelFor(brandSlug, LlmUseCase.FOLLOW_UP))
                .maxTokens(80)
                .system(systemPrompt)
                .addMessage(LlmMessage.text(LlmMessage.Role.USER, "Ask the question."))
                .build();

        return Uni.createFrom().completionStage(() -> llmProviderResolver.clientFor(brandSlug).createMessage(request))
                .map(response -> {
                    String question = response.text() != null && !response.text().isBlank()
                            ? response.text().trim()
                            : "What is the " + description + "?";
                    LOGGER.infof("[OtsGraph] generated question for var=%s: %s", varName, question);
                    return Map.<String, Object>of(OtsState.NEXT_QUESTION, question);
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> startStreamNode(OtsState state) {
        UUID scriptId = UUID.fromString(state.scriptId());
        String brandSlug = state.brandSlug();
        Map<String, Object> vars = new HashMap<>(state.collectedVars());

        return oneTimeStreamService.run(brandSlug, scriptId, vars, true, SuperUser.build())
                .map(stream -> {
                    String mixplaUrl = "https://mixpla.online/" + stream.getSlugName();
                    LOGGER.infof("[OtsGraph] stream started slugName=%s url=%s", stream.getSlugName(), mixplaUrl);
                    return Map.<String, Object>of(OtsState.MIXPLA_URL, mixplaUrl);
                })
                .subscribeAsCompletionStage();
    }

    // --- Public API ---

    /**
     * Process one user turn in an active OTS session.
     * Routes to the graph, then either asks next question or starts the stream.
     */
    public Uni<OtsResult> processUserTurn(String connectionId, String userMessage) {
        OtsSessionData session = sessionManager.get(connectionId)
                .orElseThrow(() -> new IllegalStateException("No active OTS session for " + connectionId));

        Map<String, Object> initData = buildStateMap(session, userMessage);

        return Uni.createFrom().item(() -> {
            try {
                return compiledGraph.invoke(initData)
                        .orElseThrow(() -> new RuntimeException("OtsGraph returned empty state"));
            } catch (Exception e) {
                throw new RuntimeException("OtsGraph execution failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(finalState -> {
                    if ("start".equals(finalState.action())) {
                        sessionManager.end(connectionId);
                        return new OtsResult(OtsResult.Action.STREAM_STARTED, finalState.mixplaUrl(), null);
                    } else {
                        session.setCollectedVars(finalState.collectedVars());
                        session.setPendingVarName(finalState.nextPendingVar());
                        sessionManager.update(connectionId, session);
                        return new OtsResult(OtsResult.Action.ASK_QUESTION, null, finalState.nextQuestion());
                    }
                });
    }

    /**
     * Generate the first question for a newly created session.
     * Sets the initial pendingVarName on the session.
     */
    public Uni<String> generateFirstQuestion(OtsSessionData session) {
        Map<String, Object> initData = buildStateMap(session, "");

        return Uni.createFrom().item(() -> {
            try {
                return compiledGraph.invoke(initData)
                        .orElseThrow(() -> new RuntimeException("OtsGraph returned empty state"));
            } catch (Exception e) {
                throw new RuntimeException("OtsGraph execution failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(finalState -> {
                    session.setPendingVarName(finalState.nextPendingVar());
                    return finalState.nextQuestion();
                });
    }

    private String loadResource(String path) {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> buildStateMap(OtsSessionData session, String userMessage) {
        Map<String, Object> state = new HashMap<>();
        state.put(OtsState.USER_MESSAGE, userMessage);
        state.put(OtsState.PENDING_VAR_NAME, session.getPendingVarName() != null ? session.getPendingVarName() : "");
        state.put(OtsState.COLLECTED_VARS, new HashMap<>(session.getCollectedVars()));
        state.put(OtsState.REQUIRED_VAR_NAMES, new ArrayList<>(session.getVarNames()));
        state.put(OtsState.VAR_DESCRIPTIONS, new HashMap<>(session.getVarDescriptions()));
        state.put(OtsState.BRAND_SLUG, session.getBrandSlug());
        state.put(OtsState.SCRIPT_ID, session.getScriptId().toString());
        state.put(OtsState.SCRIPT_NAME, session.getScriptName());
        state.put(OtsState.DJ_NAME, session.getDjName());
        return state;
    }
}
