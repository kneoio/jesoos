package com.semantyca.jesoos.service.help;

import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import com.semantyca.jesoos.service.chat.llm.*;
import com.semantyca.jesoos.service.knowledge.Audience;
import com.semantyca.jesoos.service.knowledge.KnowledgeBase;
import com.semantyca.jesoos.service.knowledge.SearchPlatformKnowledgeTool;
import com.semantyca.jesoos.service.knowledge.SearchPlatformKnowledgeToolHandler;
import com.semantyca.jesoos.ws.HelpChatController;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * Public help agent: knowledge lookup only, no auth, no listener, no brand.
 */
@ApplicationScoped
public class HelpAgent {

    private static final Logger LOGGER = Logger.getLogger(HelpAgent.class);
    private static final int MAX_TOOL_ITERATIONS = 4;
    private static final String LLM_SLUG = HelpChatService.SCOPE_KEY;

    /** Anonymous callers only ever see the public audience. */
    private static final Set<Audience> PUBLIC_AUDIENCE = EnumSet.of(Audience.USER);

    @Inject BrandLlmProviderResolver llmProviderResolver;
    @Inject HelpChatController controller;
    @Inject KnowledgeBase knowledgeBase;

    private CompiledGraph<HelpState> compiledGraph;

    @PostConstruct
    void init() {
        try {
            compiledGraph = new StateGraph<>(new HelpStateSerializer())
                    .addNode("llm", this::llmNode)
                    .addNode("tool", this::toolNode)
                    .addEdge(START, "llm")
                    .addConditionalEdges("llm",
                            state -> CompletableFuture.completedFuture(
                                    state.toolCall() != null && state.iteration() < MAX_TOOL_ITERATIONS ? "tool" : END),
                            Map.of("tool", "tool", END, END))
                    .addEdge("tool", "llm")
                    .compile();
            LOGGER.info("[HelpAgent] compiled successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile HelpAgent", e);
        }
    }

    private CompletableFuture<Map<String, Object>> llmNode(HelpState state) {
        String model = state.iteration() == 0
                ? llmProviderResolver.modelFor(LLM_SLUG, LlmUseCase.MAIN_CHAT)
                : llmProviderResolver.modelFor(LLM_SLUG, LlmUseCase.FOLLOW_UP);

        LlmRequest request = LlmRequest.builder()
                .maxTokens(1024L)
                .systemStable(state.systemPrompt())
                .cacheSystem(true)
                .messages(state.history())
                .model(model)
                .tools(List.of(SearchPlatformKnowledgeTool.toTool()))
                .build();

        String connectionId = state.connectionId();
        String assistantName = state.assistantName();

        return Uni.createFrom().completionStage(() ->
                        llmProviderResolver.clientFor(LLM_SLUG).streamMessage(request, delta -> {
                            if (delta == null || delta.isEmpty()) return;
                            controller.sendToConnection(connectionId,
                                    ChatMessageDTO.chunk(delta, assistantName, connectionId).build().toJson());
                        }))
                .map(response -> {
                    Map<String, Object> updates = new HashMap<>();
                    if (response.toolCall().isPresent()) {
                        LOGGER.infof("[HelpAgent] llm→tool=%s iter=%d",
                                response.toolCall().get().name(), state.iteration());
                        updates.put(HelpState.TOOL_CALL, response.toolCall().get());
                        updates.put(HelpState.BOT_RESPONSE, null);
                        updates.put(HelpState.RESPONSE_STREAMED, false);
                    } else {
                        LOGGER.infof("[HelpAgent] llm→text(streamed) iter=%d", state.iteration());
                        updates.put(HelpState.TOOL_CALL, null);
                        updates.put(HelpState.BOT_RESPONSE, response.text());
                        updates.put(HelpState.RESPONSE_STREAMED, true);
                    }
                    return updates;
                })
                .onFailure().recoverWithItem(err -> {
                    LOGGER.errorf(err, "[HelpAgent] llmNode failed");
                    return Map.of(HelpState.TOOL_CALL, (Object) null, HelpState.BOT_RESPONSE, "",
                            HelpState.RESPONSE_STREAMED, false);
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> toolNode(HelpState state) {
        LlmToolCall toolCall = state.toolCall();
        List<LlmMessage> history = new ArrayList<>(state.history());
        String connectionId = state.connectionId();

        if ("search_platform_knowledge".equals(toolCall.name())) {
            controller.sendToConnection(connectionId,
                    ChatMessageDTO.processing("Looking up Mixpla knowledge...", connectionId).build().toJson());
        }

        return executeToolCall(toolCall)
                .map(result -> {
                    history.add(LlmMessage.toolUse(toolCall));
                    history.add(LlmMessage.toolResult(toolCall.id(), result.payload()));

                    Map<String, Object> updates = new HashMap<>();
                    updates.put(HelpState.HISTORY, history);
                    updates.put(HelpState.TOOL_CALL, null);
                    updates.put(HelpState.ITERATION, state.iteration() + 1);
                    return updates;
                })
                .onFailure().recoverWithItem(err -> {
                    LOGGER.errorf(err, "[HelpAgent] toolNode failed for tool=%s", toolCall.name());
                    history.add(LlmMessage.toolUse(toolCall));
                    history.add(LlmMessage.toolResult(toolCall.id(),
                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
                    return Map.of(HelpState.HISTORY, history, HelpState.TOOL_CALL, (Object) null,
                            HelpState.ITERATION, state.iteration() + 1);
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private Uni<ToolNodeResult> executeToolCall(LlmToolCall toolCall) {
        if ("search_platform_knowledge".equals(toolCall.name())) {
            return SearchPlatformKnowledgeToolHandler.execute(toolCall.input(), knowledgeBase, PUBLIC_AUDIENCE);
        }
        LOGGER.warnf("[HelpAgent] unknown tool: %s", toolCall.name());
        return Uni.createFrom().item(ToolNodeResult.ok(
                new JsonObject().put("ok", false).put("error", "Unknown tool: " + toolCall.name()).encode()));
    }

    public Uni<HelpState> run(Map<String, Object> initData) {
        return Uni.createFrom().item(() -> {
            try {
                return compiledGraph.invoke(initData)
                        .orElseThrow(() -> new RuntimeException("HelpAgent returned empty state"));
            } catch (Exception e) {
                throw new RuntimeException("HelpAgent execution failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
