package com.semantyca.jesoos.service.chat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.repository.exception.ext.UserAlreadyExistsException;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.ResourceUtil;
import com.semantyca.core.util.WebHelper;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.model.cnst.ChatType;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.service.chat.tools.AddToQueueTool;
import com.semantyca.jesoos.service.chat.tools.AudienceTool;
import com.semantyca.jesoos.service.chat.tools.AudienceToolHandler;
import com.semantyca.jesoos.service.chat.tools.GetStations;
import com.semantyca.jesoos.service.chat.tools.GetStationsToolHandler;
import com.semantyca.jesoos.service.chat.tools.ListenerDataTool;
import com.semantyca.jesoos.service.chat.tools.ListenerDataToolHandler;
import com.semantyca.jesoos.service.chat.tools.PerplexitySearchTool;
import com.semantyca.jesoos.service.chat.tools.PerplexitySearchToolHandler;
import com.semantyca.jesoos.service.chat.tools.SearchBrandSoundFragments;
import com.semantyca.jesoos.service.chat.tools.SearchBrandSoundFragmentsToolHandler;
import com.semantyca.jesoos.service.chat.tools.SendEmailToOwnerTool;
import com.semantyca.jesoos.service.chat.tools.SendEmailToOwnerToolHandler;
import com.semantyca.jesoos.service.live.AiHelperService;
import com.semantyca.officeframe.service.LabelService;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

@ApplicationScoped
public class PublicChatService extends ChatService {

    protected PublicChatService() {
        super(null, null);
    }

    @Inject
    public PublicChatService(JesoosConfig config, AiHelperService aiHelperService) {
        super(config, aiHelperService);
    }

    @Inject
    PublicChatSessionManager sessionManager;

    @Inject
    ListenerService listenerService;

    @Inject
    BrandService brandService;

    @Override
    protected ChatType getChatType() {
        return ChatType.PUBLIC;
    }

    @Inject
    UserService userService;

    @Inject
    LabelService labelService;

    @Inject
    ReactiveMailer reactiveMailer;

    public Uni<RegistrationResult> registerListener(String email, String stationSlug, String userName) {

        ListenerDTO dto = new ListenerDTO();
        dto.setEmail(email);
        dto.getLocalizedName().put(LanguageCode.en, userName);

        return listenerService.upsert(null,dto, stationSlug, SuperUser.build())
                .onFailure(UserAlreadyExistsException.class).recoverWithUni(throwable -> {
                    String slugName = WebHelper.generateSlug(userName != null && !userName.isBlank() ? userName : email);
                    return userService.findByLogin(slugName)
                            .onItem().transformToUni(existingUser -> {
                                if (existingUser.getId() == 0) {
                                    return Uni.createFrom().failure(throwable);
                                }
                                ListenerDTO existingDto = new ListenerDTO();
                                existingDto.setUserId(existingUser.getId());
                                existingDto.setSlugName(slugName);
                                return Uni.createFrom().item(existingDto);
                            });
                })
                .onItem().transformToUni(listenerDTO -> {
                    String userToken = UUID.randomUUID().toString();
                    sessionManager.storeUserToken(userToken, email);
                    return Uni.createFrom().item(new RegistrationResult(
                            listenerDTO.getUserId(),
                            userToken
                    ));
                });
    }

    public Uni<String> refreshToken(String oldToken) {
        String email = sessionManager.validateSessionAndGetEmail(oldToken);
        if (email == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid or expired token"));
        }

        return userService.findByEmail(email)
                .onItem().transformToUni(user -> {
                    if (user == null || user.getId() == 0) {
                        return Uni.createFrom().failure(new IllegalArgumentException("User not found"));
                    }
                    String newToken = UUID.randomUUID().toString();
                    sessionManager.storeUserToken(newToken, email);
                    return Uni.createFrom().item(newToken);
                });
    }

    public Uni<IUser> authenticateUserFromToken(String token) {
        if (token == null || token.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Token is required"));
        }

        String email = sessionManager.validateSessionAndGetEmail(token);
        if (email == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid or expired token"));
        }

        return userService.findByEmail(email)
                .onItem().transformToUni(user -> {
                    if (user == null || user.getId() == 0) {
                        return Uni.createFrom().item(AnonymousUser.build());
                    }
                    return Uni.createFrom().item(user);
                });
    }

    public Uni<Void> ensureUserIsListenerOfStation(long userId, String stationSlug) {
        return listenerService.getByUserId(userId)
                .chain(listener -> {
                    if (listener == null) {
                        return Uni.createFrom().voidItem();
                    }

                    return brandService.getBySlugName(stationSlug)
                            .chain(station -> {
                                if (station == null) {
                                    return Uni.createFrom().voidItem();
                                }

                                return listenerService.getListenersBrands(listener.getId())
                                        .chain(currentStations -> {
                                            if (!currentStations.contains(station.getId())) {
                                                return listenerService.addBrandToListener(listener.getId(), station.getId());
                                            }

                                            return Uni.createFrom().voidItem();
                                        });
                            });
                });
    }

    @Override
    protected List<Tool> getAvailableTools() {
        return List.of(
                GetStations.toTool(),
                SearchBrandSoundFragments.toTool(),
                AddToQueueTool.toTool(),
                PerplexitySearchTool.toTool(),
                AudienceTool.toTool(),
                ListenerDataTool.toTool(),
                SendEmailToOwnerTool.toTool()
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

        if ("get_stations".equals(toolUse.name())) {
            return GetStationsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("search_brand_sound_fragments".equals(toolUse.name())) {
            return SearchBrandSoundFragmentsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("perplexity_search".equals(toolUse.name())) {
            return PerplexitySearchToolHandler.handle(
                    toolUse, inputMap, perplexitySearchHelper, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("listener".equals(toolUse.name())) {
            return AudienceToolHandler.handle(
                    toolUse, inputMap, listenerService, brandName, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("listener_data".equals(toolUse.name())) {
            return ListenerDataToolHandler.handle(
                    toolUse, inputMap, listenerService, labelService, brandName, userId, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("send_email_to_owner".equals(toolUse.name())) {
            return SendEmailToOwnerToolHandler.handle(
                    toolUse, inputMap, brandService, userService, reactiveMailer, config.getFromAddress(), userId, brandName, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else {
            return Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
        }
    }

    @Override
    protected String getMainPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/publicMainPrompt.hbs");
            return !custom.isBlank() ? custom : super.getMainPrompt();
        } catch (Exception ignored) {
            return super.getMainPrompt();
        }
    }

    @Override
    protected String getFollowUpPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/publicFollowUpPrompt.hbs");
            return !custom.isBlank() ? custom : super.getFollowUpPrompt();
        } catch (Exception ignored) {
            return super.getFollowUpPrompt();
        }
    }
}
