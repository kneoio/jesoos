package com.semantyca.jesoos.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.mixpla.model.cnst.PromptType;
import com.semantyca.mixpla.model.filter.PromptFilter;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class ListGeneratorPromptsToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListGeneratorPromptsToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            PromptService promptService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        ListGeneratorPromptsToolHandler handler = new ListGeneratorPromptsToolHandler();
        String brandName = inputMap.getOrDefault("brandName", JsonValue.from("")).toString();

        handler.sendProcessingChunk(chunkHandler, connectionId, "Fetching available generator prompts for " + brandName + "...");

        PromptFilter filter = new PromptFilter();
        filter.setPromptType(PromptType.GENERATOR);
        filter.setEnabled(true);
        filter.setMaster(true);

        return promptService.getAll(100, 0, SuperUser.build(), filter)
                .flatMap(prompts -> {
                    JsonArray promptArray = new JsonArray();
                    for (var prompt : prompts) {
                        promptArray.add(new JsonObject()
                                .put("id", prompt.getId().toString())
                                .put("title", prompt.getTitle())
                                .put("description", prompt.getDescription())
                                .put("languageTag", prompt.getLanguageTag().tag()));
                    }

                    String resultMessage = new JsonObject()
                            .put("brandName", brandName)
                            .put("count", prompts.size())
                            .put("prompts", promptArray)
                            .encode();

                    LOGGER.info("[ListGeneratorPrompts] Found {} generator prompts", prompts.size());

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, resultMessage, conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListGeneratorPrompts] Failed to fetch prompts", err);
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "Failed to fetch generator prompts: " + err.getMessage());
                    return Uni.createFrom().voidItem();
                });
    }
}
