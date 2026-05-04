package com.semantyca.jesoos.service.chat.tools.ots;

import com.semantyca.core.model.ScriptVariable;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.service.OneTimeStreamService;
import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.jesoos.service.chat.ots.OtsGraph;
import com.semantyca.jesoos.service.chat.ots.OtsSessionData;
import com.semantyca.jesoos.service.chat.ots.OtsSessionManager;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.chat.tools.BaseToolHandler;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class StartOneTimeStreamToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartOneTimeStreamToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            OneTimeStreamService oneTimeStreamService,
            ScriptService scriptService,
            OtsSessionManager otsSessionManager,
            OtsGraph otsGraph,
            String streamHost,
            String djName,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        StartOneTimeStreamToolHandler handler = new StartOneTimeStreamToolHandler();
        String brandSlugName = (String) inputMap.getOrDefault("brandSlugName", "");
        String scriptIdStr = (String) inputMap.getOrDefault("scriptId", "");

        if (brandSlugName.isEmpty() || scriptIdStr.isEmpty()) {
            return handler.buildErrorResponse(toolCall, "Missing required parameters: brandSlugName, scriptId", conversationHistory, systemPromptCall2, streamFn);
        }

        UUID scriptId;
        try {
            scriptId = UUID.fromString(scriptIdStr);
        } catch (Exception e) {
            return handler.buildErrorResponse(toolCall, "Invalid scriptId format", conversationHistory, systemPromptCall2, streamFn);
        }

        Map<String, Object> userVariables = new HashMap<>();
        if (inputMap.get("userVariables") instanceof Map<?, ?> vars) {
            vars.forEach((k, v) -> userVariables.put(k.toString(), v));
        }

        boolean startImmediately = true;
        if (inputMap.containsKey("startImmediately")) {
            startImmediately = Boolean.parseBoolean(inputMap.get("startImmediately").toString());
        }
        boolean finalStartImmediately = startImmediately;

        return scriptService.getById(scriptId, SuperUser.build())
                .flatMap(script -> {
                    List<ScriptVariable> requiredVars = script.getRequiredVariables();

                    if (requiredVars != null && !requiredVars.isEmpty()) {
                        List<String> missing = requiredVars.stream()
                                .filter(v -> !userVariables.containsKey(v.getName()) ||
                                        userVariables.get(v.getName()) == null ||
                                        userVariables.get(v.getName()).toString().isBlank())
                                .map(ScriptVariable::getName)
                                .collect(Collectors.toList());

                        if (!missing.isEmpty()) {
                            handler.sendProcessingChunk(chunkHandler, connectionId, "Setting up stream...");
                            List<String> varNames = requiredVars.stream().map(ScriptVariable::getName).collect(Collectors.toList());
                            Map<String, String> varDescriptions = requiredVars.stream()
                                    .collect(Collectors.toMap(ScriptVariable::getName, ScriptVariable::getDescription));

                            OtsSessionData session = new OtsSessionData(brandSlugName, scriptId, script.getName(), varNames, varDescriptions);
                            session.setDjName(djName);
                            otsSessionManager.start(connectionId, session);

                            return otsGraph.generateFirstQuestion(session)
                                    .flatMap(firstQuestion -> {
                                        JsonObject payload = new JsonObject()
                                                .put("action", "collect_variables")
                                                .put("firstQuestion", firstQuestion)
                                                .put("missingVars", new io.vertx.core.json.JsonArray(new ArrayList<>(missing)));
                                        handler.addToolUseToHistory(toolCall, conversationHistory);
                                        handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                                        return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                                    });
                        }
                    }

                    handler.sendProcessingChunk(chunkHandler, connectionId, "Starting one-time stream...");
                    return oneTimeStreamService.run(brandSlugName, scriptId, userVariables, finalStartImmediately, SuperUser.build())
                            .flatMap(stream -> {
                                String hlsUrl = streamHost + "/" + stream.getSlugName() + "/radio/stream.m3u8";
                                String mixplaUrl = "https://mixpla.online/" + stream.getSlugName();
                                JsonObject payload = new JsonObject()
                                        .put("ok", true).put("slugName", stream.getSlugName())
                                        .put("id", stream.getId().toString()).put("status", stream.getStatus().name())
                                        .put("hlsUrl", hlsUrl).put("mixplaUrl", mixplaUrl);
                                handler.sendProcessingChunk(chunkHandler, connectionId, "Stream started: " + stream.getSlugName());
                                handler.addToolUseToHistory(toolCall, conversationHistory);
                                handler.addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
                                return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                            })
                            .onFailure().recoverWithUni(err -> {
                                LOGGER.error("[StartOneTimeStream] Failed", err);
                                return handler.buildErrorResponse(toolCall, err.getMessage(), conversationHistory, systemPromptCall2, streamFn);
                            });
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[StartOneTimeStream] Script lookup failed", err);
                    return handler.buildErrorResponse(toolCall, err.getMessage(), conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private Uni<Void> buildErrorResponse(LlmToolCall toolCall, String message,
                                          List<LlmMessage> conversationHistory, String systemPromptCall2,
                                          Function<LlmRequest, Uni<Void>> streamFn) {
        JsonObject payload = new JsonObject().put("ok", false).put("error", message);
        addToolUseToHistory(toolCall, conversationHistory);
        addToolResultToHistory(toolCall, payload.encode(), conversationHistory);
        return streamFn.apply(buildFollowUpParams(systemPromptCall2, conversationHistory));
    }
}
