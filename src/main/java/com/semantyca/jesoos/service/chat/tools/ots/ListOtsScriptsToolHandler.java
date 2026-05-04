package com.semantyca.jesoos.service.chat.tools.ots;

import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.jesoos.service.chat.llm.LlmMessage;
import com.semantyca.jesoos.service.chat.llm.LlmRequest;
import com.semantyca.jesoos.service.chat.llm.LlmToolCall;
import com.semantyca.jesoos.service.chat.tools.BaseToolHandler;
import com.semantyca.mixpla.model.cnst.SceneTimingMode;
import com.semantyca.mixpla.model.filter.ScriptFilter;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class ListOtsScriptsToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListOtsScriptsToolHandler.class);

    public static Uni<Void> handle(
            LlmToolCall toolCall,
            Map<String, Object> inputMap,
            ScriptService scriptService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<LlmMessage> conversationHistory,
            String systemPromptCall2,
            Function<LlmRequest, Uni<Void>> streamFn
    ) {
        ListOtsScriptsToolHandler handler = new ListOtsScriptsToolHandler();
        ScriptFilter filter = new ScriptFilter();
        filter.setTimingMode(SceneTimingMode.RELATIVE_TO_STREAM_START);

        return scriptService.getAll(50, 0, filter)
                .flatMap(scripts -> {
                    JsonArray result = new JsonArray();
                    scripts.forEach(script -> {
                        JsonObject entry = new JsonObject()
                                .put("id", script.getId().toString())
                                .put("name", script.getName());
                        if (script.getRequiredVariables() != null && !script.getRequiredVariables().isEmpty()) {
                            JsonArray vars = new JsonArray();
                            script.getRequiredVariables().forEach(v -> vars.add(new JsonObject()
                                    .put("name", v.getName()).put("description", v.getDescription())
                                    .put("type", v.getType()).put("required", v.isRequired())));
                            entry.put("requiredVariables", vars);
                        }
                        result.add(entry);
                    });
                    LOGGER.info("[ListOtsScripts] Found {} scripts", scripts.size());
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, result.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListOtsScripts] Failed", err);
                    JsonObject errorPayload = new JsonObject().put("ok", false).put("error", err.getMessage());
                    handler.addToolUseToHistory(toolCall, conversationHistory);
                    handler.addToolResultToHistory(toolCall, errorPayload.encode(), conversationHistory);
                    return streamFn.apply(handler.buildFollowUpParams(systemPromptCall2, conversationHistory));
                });
    }
}
