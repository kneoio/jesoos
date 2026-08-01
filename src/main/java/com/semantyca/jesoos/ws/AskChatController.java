package com.semantyca.jesoos.ws;

import com.semantyca.core.controller.AbstractSecuredController;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.service.ask.AskAuthService;
import com.semantyca.jesoos.service.ask.AskChatService;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal Mixpla Ask WebSocket — isolated from {@link PublicChatController}.
 * No brandSlug; platform-scoped chat only, OIDC-authenticated callers only.
 */
@ApplicationScoped
public class AskChatController extends AbstractSecuredController<Object, Object> {

    private static final Logger LOG = Logger.getLogger(AskChatController.class);
    private static final SecureRandom CONNECTION_ID_RANDOM = new SecureRandom();

    private final AskChatService askChatService;
    private final AskAuthService askAuthService;
    private final Map<String, ServerWebSocket> activeConnections = new ConcurrentHashMap<>();

    public AskChatController() {
        super(null);
        this.askChatService = null;
        this.askAuthService = null;
    }

    @Inject
    public AskChatController(UserService userService, AskChatService askChatService, AskAuthService askAuthService) {
        super(userService);
        this.askChatService = askChatService;
        this.askAuthService = askAuthService;
        if (askChatService != null) {
            askChatService.setController(this);
        }
    }

    public void setupRoutes(Router router) {
        router.route("/jesoos/ws/ask").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                String token = rc.request().getParam("token");
                assert askAuthService != null;
                askAuthService.authenticate(token)
                        .subscribe().with(
                                auth -> {
                                    IUser user = auth.user();
                                    if (AskAuthService.isAnonymous(user)) {
                                        LOG.warnf("[ask-ws-auth] rejected — token missing or not resolvable");
                                        rc.response().setStatusCode(401).end("Authentication required");
                                        return;
                                    }
                                    LOG.infof("[ask-ws-auth] token OK — userId=%d", user.getId());
                                    rc.request().toWebSocket()
                                            .onSuccess(ws -> handleAskWebSocket(ws, user))
                                            .onFailure(err -> {
                                                LOG.error("Ask WebSocket connection failed", err);
                                                rc.fail(500, err);
                                            });
                                },
                                err -> {
                                    LOG.warnf("[ask-ws-auth] token validation failed — %s", err.getMessage());
                                    rc.response().setStatusCode(401).end("Invalid or expired token");
                                }
                        );
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });
    }

    private void handleAskWebSocket(ServerWebSocket webSocket, IUser user) {
        webSocket.accept();

        String connectionId = newConnectionId();
        activeConnections.put(connectionId, webSocket);

        assert askChatService != null;
        askChatService.bootstrapConnectionHistory(connectionId, user.getId());
        String userName = user.getLogin() != null ? user.getLogin() : "";
        askChatService.resolveLabelsForUser(user.getId())
                .subscribe().with(
                        labels -> webSocket.writeTextMessage(sessionMessage(userName, labels)),
                        err -> {
                            LOG.warnf(err, "[ask-ws-auth] labels resolve failed userId=%d", user.getId());
                            webSocket.writeTextMessage(sessionMessage(userName, java.util.List.of()));
                        });

        LOG.infof("Ask chat WebSocket connected: %s user=%s", connectionId, user.getUserName());

        webSocket.textMessageHandler(message -> {
            try {
                JsonObject msgJson = new JsonObject(message);
                String action = msgJson.getString("action");
                switch (action) {
                    case "sendMessage" -> handleUserMessage(webSocket, msgJson, connectionId, user);
                    case "getHistory" -> handleGetHistory(webSocket, msgJson, connectionId, user);
                    default -> sendError(webSocket, "Unknown action: " + action);
                }
            } catch (Exception e) {
                LOG.error("Error processing ask message", e);
                sendError(webSocket, "Invalid message format: " + e.getMessage());
            }
        });

        webSocket.closeHandler(v -> {
            askChatService.persistConnectionHistory(connectionId, user.getId());
            activeConnections.remove(connectionId);
            LOG.infof("Ask chat WebSocket closed: %s", connectionId);
        });

        webSocket.exceptionHandler(err -> {
            LOG.errorf(err, "Ask WebSocket error for %s", connectionId);
            activeConnections.remove(connectionId);
        });
    }

    private static String sessionMessage(String userName, java.util.List<String> labels) {
        return new JsonObject()
                .put("type", "session_token")
                .put("userName", userName)
                .put("labels", new JsonArray(labels))
                .encode();
    }

    private void handleUserMessage(ServerWebSocket webSocket, JsonObject msgJson, String connectionId, IUser user) {
        String content = msgJson.getString("content");
        if (content == null || content.trim().isEmpty()) {
            sendError(webSocket, "Message content cannot be empty");
            return;
        }
        if (content.length() > 2000) {
            sendError(webSocket, "Message too long (max 2000 characters)");
            return;
        }

        String username = user.getLogin() != null ? user.getLogin() : "user";

        assert askChatService != null;
        askChatService.processUserMessage(username, content, connectionId, user)
                .subscribe().with(
                        response -> {
                            webSocket.writeTextMessage(response);
                            webSocket.writeTextMessage(ChatMessageDTO.processing("...", connectionId).build().toJson());
                            askChatService.generateBotResponse(
                                    content,
                                    webSocket::writeTextMessage,
                                    webSocket::writeTextMessage,
                                    connectionId,
                                    user
                            ).subscribe().with(
                                    v -> {},
                                    e -> {
                                        LOG.error("Ask bot response error", e);
                                        sendError(webSocket, "Bot response failed: " + e.getMessage());
                                    }
                            );
                        },
                        err -> {
                            LOG.error("Error processing ask user message", err);
                            sendError(webSocket, err.getMessage());
                        }
                );
    }

    private void handleGetHistory(ServerWebSocket webSocket, JsonObject msgJson, String connectionId, IUser user) {
        Integer limit = msgJson.getInteger("limit", 50);
        assert askChatService != null;
        askChatService.getChatHistory(limit, connectionId, user)
                .subscribe().with(
                        webSocket::writeTextMessage,
                        err -> {
                            LOG.error("Error getting ask chat history", err);
                            sendError(webSocket, err.getMessage());
                        }
                );
    }

    public void sendToConnection(String connectionId, String message) {
        ServerWebSocket ws = activeConnections.get(connectionId);
        if (ws != null && !ws.isClosed()) {
            ws.writeTextMessage(message);
        }
    }

    private static String newConnectionId() {
        byte[] buf = new byte[12];
        CONNECTION_ID_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private void sendError(ServerWebSocket webSocket, String message) {
        webSocket.writeTextMessage(ChatMessageDTO.error(message, "system", "system").build().toJson());
    }
}
