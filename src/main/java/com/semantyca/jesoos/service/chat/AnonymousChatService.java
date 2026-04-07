package com.semantyca.jesoos.service.chat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.external.KeycloakAuthService;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.chat.tools.*;
import com.semantyca.jesoos.service.live.AiHelperService;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@ApplicationScoped
public class AnonymousChatService extends ChatService {

    @Inject
    BrandService brandService;

    @Inject
    UserService userService;

    @Inject
    ReactiveMailer reactiveMailer;

    @Inject
    KeycloakAuthService keycloakAuthService;

    @Inject
    PublicChatSessionManager sessionManager;

    protected AnonymousChatService() {
        super(null, null);
    }

    @Inject
    public AnonymousChatService(JesoosConfig config, AiHelperService aiHelperService) {
        super(config, aiHelperService);
    }

    @Override
    protected ChatType getChatType() {
        return ChatType.PUBLIC;
    }

    @Override
    protected List<Tool> getAvailableTools() {
        return List.of(
                GetStations.toTool(),
                SendEmailToOwnerTool.toTool(),
                StartAuthTool.toTool(),
                VerifyCode.toTool()
        );
    }

    @Override
    protected MessageCreateParams buildMessageCreateParams(String renderedPrompt, List<MessageParam> history) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(1024L)
                .system(renderedPrompt)
                .messages(history)
                .model(Model.CLAUDE_HAIKU_4_5_20251001);

        for (Tool tool : getAvailableTools()) {
            builder.addTool(tool);
        }

        return builder.build();
    }

    @Override
    protected Uni<Void> handleToolCall(ToolUseBlock toolUse,
                                       Consumer<String> chunkHandler,
                                       Consumer<String> completionHandler,
                                       String connectionId,
                                       String brandName,
                                       long userId,
                                       List<MessageParam> conversationHistory) {

        Map<String, JsonValue> inputMap = extractInputMap(toolUse);
        Function<MessageCreateParams, Uni<Void>> streamFn =
                createStreamFunction(chunkHandler, completionHandler, connectionId, brandName, userId);

        if ("send_email_to_owner".equals(toolUse.name())) {
            return SendEmailToOwnerToolHandler.handle(
                    toolUse, inputMap, brandService, userService, reactiveMailer, config.getFromAddress(), userId, brandName, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("start_auth".equals(toolUse.name())) {
            return StartAuthToolHandler.handle(
                    toolUse, inputMap, keycloakAuthService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("verify_code".equals(toolUse.name())) {
            return VerifyCodeToolHandler.handle(
                    toolUse, inputMap, sessionManager, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else {
            return Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
        }
    }

    @Override
    protected String getMainPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/anonymousMainPrompt.hbs");
            return !custom.isBlank() ? custom : super.getMainPrompt();
        } catch (Exception ignored) {
            return super.getMainPrompt();
        }
    }

    @Override
    protected String getFollowUpPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/anonymousFollowUpPrompt.hbs");
            return !custom.isBlank() ? custom : super.getFollowUpPrompt();
        } catch (Exception ignored) {
            return super.getFollowUpPrompt();
        }
    }
}
