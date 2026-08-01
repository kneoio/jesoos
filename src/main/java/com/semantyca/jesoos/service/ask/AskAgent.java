package com.semantyca.jesoos.service.ask;

import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.knowledge.Audience;
import com.semantyca.jesoos.service.knowledge.KnowledgeBase;
import com.semantyca.jesoos.service.knowledge.SearchPlatformKnowledgeTool;
import com.semantyca.jesoos.service.knowledge.SearchPlatformKnowledgeToolHandler;
import com.semantyca.jesoos.service.chat.ToolNodeResult;
import com.semantyca.jesoos.service.chat.llm.*;
import com.semantyca.jesoos.service.chat.tools.ListenerDataTool;
import com.semantyca.jesoos.service.chat.tools.ListenerDataToolHandler;
import com.semantyca.jesoos.service.chat.tools.ListenerLabelCache;
import com.semantyca.jesoos.ws.AskChatController;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

@ApplicationScoped
public class AskAgent {

    private static final Logger LOGGER = Logger.getLogger(AskAgent.class);
    private static final int MAX_TOOL_ITERATIONS = 8;
    private static final String LLM_SLUG = AskChatService.SCOPE_KEY;

    @Inject BrandLlmProviderResolver llmProviderResolver;
    @Inject AskChatController controller;
    @Inject ListenerService listenerService;
    @Inject ListenerLabelCache listenerLabelCache;
    @Inject KnowledgeBase knowledgeBase;

    private CompiledGraph<AskState> compiledGraph;

    @PostConstruct
    void init() {
        try {
            compiledGraph = new StateGraph<>(new AskStateSerializer())
                    .addNode("loadContext", this::loadContextNode)
                    .addNode("llm", this::llmNode)
                    .addNode("tool", this::toolNode)
                    .addEdge(START, "loadContext")
                    .addEdge("loadContext", "llm")
                    .addConditionalEdges("llm",
                            state -> CompletableFuture.completedFuture(
                                    state.toolCall() != null && state.iteration() < MAX_TOOL_ITERATIONS ? "tool" : END),
                            Map.of("tool", "tool", END, END))
                    .addEdge("tool", "llm")
                    .compile();
            LOGGER.info("[AskAgent] compiled successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile AskAgent", e);
        }
    }

    private CompletableFuture<Map<String, Object>> loadContextNode(AskState state) {
        long userId = state.userId();
        if (userId == 0) {
            return CompletableFuture.completedFuture(AskListenerContext.toStateUpdates(AskListenerContext.empty()));
        }
        return listenerService.getByUserId(userId)
                .map(listener -> AskListenerContext.toStateUpdates(
                        AskListenerContext.from(listener, listenerLabelCache)))
                .onFailure().recoverWithItem(err -> {
                    LOGGER.warnf("[AskAgent] loadContext listener fetch failed userId=%d: %s", userId, err.getMessage());
                    return AskListenerContext.toStateUpdates(AskListenerContext.empty());
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private CompletableFuture<Map<String, Object>> llmNode(AskState state) {
        Set<Audience> audiences = state.audiences();
        List<LlmTool> tools = List.of(SearchPlatformKnowledgeTool.toTool(), ListenerDataTool.toTool());

        String model = state.iteration() == 0
                ? llmProviderResolver.modelFor(LLM_SLUG, LlmUseCase.MAIN_CHAT)
                : llmProviderResolver.modelFor(LLM_SLUG, LlmUseCase.FOLLOW_UP);

        String base = state.systemPrompt()
                .replace("{{audience}}", Audience.primary(audiences).identifier());

        LlmRequest request = LlmRequest.builder()
                .maxTokens(1024L)
                .systemStable(base)
                .systemVolatile(buildVolatileContext(state.listenerContext()))
                .cacheSystem(true)
                .messages(state.history())
                .model(model)
                .tools(tools)
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
                        LOGGER.infof("[AskAgent] llm→tool=%s userId=%d iter=%d",
                                response.toolCall().get().name(), state.userId(), state.iteration());
                        updates.put(AskState.TOOL_CALL, response.toolCall().get());
                        updates.put(AskState.BOT_RESPONSE, null);
                        updates.put(AskState.RESPONSE_STREAMED, false);
                    } else {
                        LOGGER.infof("[AskAgent] llm→text(streamed) userId=%d iter=%d", state.userId(), state.iteration());
                        updates.put(AskState.TOOL_CALL, null);
                        updates.put(AskState.BOT_RESPONSE, response.text());
                        updates.put(AskState.RESPONSE_STREAMED, true);
                    }
                    return updates;
                })
                .onFailure().recoverWithItem(err -> {
                    LOGGER.errorf(err, "[AskAgent] llmNode failed");
                    return Map.of(AskState.TOOL_CALL, (Object) null, AskState.BOT_RESPONSE, "",
                            AskState.RESPONSE_STREAMED, false);
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private static String buildVolatileContext(String listenerContext) {
        if (listenerContext == null || listenerContext.isBlank()) return "";
        return "----------------------------------------\n"
                + "CURRENT CONTEXT (live, this request)\n"
                + "----------------------------------------\n"
                + listenerContext.trim();
    }

    private CompletableFuture<Map<String, Object>> toolNode(AskState state) {
        LlmToolCall toolCall = state.toolCall();
        List<LlmMessage> history = new ArrayList<>(state.history());
        String connectionId = state.connectionId();

        String status = toolStatusMessage(toolCall.name());
        if (status != null) {
            controller.sendToConnection(connectionId,
                    ChatMessageDTO.processing(status, connectionId).build().toJson());
        }

        return executeToolCall(toolCall, state)
                .chain(result -> {
                    history.add(LlmMessage.toolUse(toolCall));
                    history.add(LlmMessage.toolResult(toolCall.id(), result.payload()));

                    Map<String, Object> updates = new HashMap<>();
                    updates.put(AskState.HISTORY, history);
                    updates.put(AskState.TOOL_CALL, null);
                    updates.put(AskState.ITERATION, state.iteration() + 1);

                    if (result.wsMessage() != null) {
                        controller.sendToConnection(connectionId, result.wsMessage());
                    }

                    return Uni.createFrom().item(updates);
                })
                .onFailure().recoverWithItem(err -> {
                    LOGGER.errorf(err, "[AskAgent] toolNode failed for tool=%s", toolCall.name());
                    history.add(LlmMessage.toolUse(toolCall));
                    history.add(LlmMessage.toolResult(toolCall.id(),
                            new JsonObject().put("ok", false).put("error", err.getMessage()).encode()));
                    return Map.of(AskState.HISTORY, history, AskState.TOOL_CALL, (Object) null,
                            AskState.ITERATION, state.iteration() + 1);
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private Uni<ToolNodeResult> executeToolCall(LlmToolCall toolCall, AskState state) {
        Map<String, Object> input = toolCall.input();
        return switch (toolCall.name()) {
            case "search_platform_knowledge" -> SearchPlatformKnowledgeToolHandler.execute(input, knowledgeBase, state.audiences());
            case "listener_data" -> ListenerDataToolHandler.execute(input, listenerService, listenerLabelCache, state.userId());
            default -> {
                LOGGER.warnf("[AskAgent] unknown tool: %s", toolCall.name());
                yield Uni.createFrom().item(ToolNodeResult.ok(
                        new JsonObject().put("ok", false).put("error", "Unknown tool: " + toolCall.name()).encode()));
            }
        };
    }

    public Uni<AskState> run(Map<String, Object> initData) {
        return Uni.createFrom().item(() -> {
            try {
                return compiledGraph.invoke(initData)
                        .orElseThrow(() -> new RuntimeException("AskAgent returned empty state"));
            } catch (Exception e) {
                throw new RuntimeException("AskAgent execution failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private static String toolStatusMessage(String toolName) {
        return switch (toolName) {
            case "search_platform_knowledge" -> "Looking up Mixpla knowledge...";
            case "listener_data" -> "Remembering...";
            default -> null;
        };
    }
}
