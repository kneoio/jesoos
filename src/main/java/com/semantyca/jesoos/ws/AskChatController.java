package com.semantyca.jesoos.ws;

import com.semantyca.core.controller.AbstractSecuredController;
import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.service.ask.AskChatService;
import com.semantyca.jesoos.service.chat.ChatAuthService;
import io.vertx.core.http.ServerWebSocket;
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
 * No brandSlug; platform-scoped chat only.
 */
@ApplicationScoped
public class AskChatController extends AbstractSecuredController<Object, Object> {

    private static final Logger LOG = Logger.getLogger(AskChatController.class);
    private static final SecureRandom CONNECTION_ID_RANDOM = new SecureRandom();

    private final AskChatService askChatService;
    private final ChatAuthService chatAuthService;
    private final Map<String, ServerWebSocket> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, UserHolder> connectionUsers = new ConcurrentHashMap<>();

    public AskChatController() {
        super(null);
        this.askChatService = null;
        this.chatAuthService = null;
    }

    @Inject
    public AskChatController(UserService userService, AskChatService askChatService, ChatAuthService chatAuthService) {
        super(userService);
        this.askChatService = askChatService;
        this.chatAuthService = chatAuthService;
        if (askChatService != null) {
            askChatService.setController(this);
        }
    }

    public void setupRoutes(Router router) {
        router.route("/jesoos/ws/ask").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                String token = rc.request().getParam("token");
                String anonId = rc.request().getParam("anonId");
                assert chatAuthService != null;
                chatAuthService.authenticateUserFromToken(token)
                        .onItem().invoke(user -> {
                            if (user instanceof AnonymousUser || user.getId() == 0) {
                                LOG.warnf("[ask-ws-auth] token resolved to anonymous");
                            } else {
                                LOG.infof("[ask-ws-auth] token OK — userId=%d", user.getId());
                            }
                        })
                        .onFailure().recoverWithItem(err -> {
                            LOG.warnf("[ask-ws-auth] token validation failed — %s", err.getMessage());
                            return AnonymousUser.build();
                        })
                        .subscribe().with(
                                user -> rc.request().toWebSocket()
                                        .onSuccess(ws -> handleAskWebSocket(ws, user, anonId, token))
                                        .onFailure(err -> {
                                            LOG.error("Ask WebSocket connection failed", err);
                                            rc.fail(500, err);
                                        }),
                                err -> rc.response().setStatusCode(401).end("Invalid or expired token")
                        );
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });
    }

    private void handleAskWebSocket(ServerWebSocket webSocket, IUser user, String anonId, String token) {
        webSocket.accept();

        String connectionId = (isAnonymous(user) && isValidAnonId(anonId))
                ? anonId
                : newConnectionId();
        activeConnections.put(connectionId, webSocket);
        UserHolder userHolder = new UserHolder(user);
        connectionUsers.put(connectionId, userHolder);

        if (!isAnonymous(user)) {
            assert askChatService != null;
            askChatService.bootstrapConnectionHistory(connectionId, user.getId());
            webSocket.writeTextMessage(new JsonObject()
                    .put("type", "session_token")
                    .put("token", token)
                    .put("userName", user.getLogin() != null ? user.getLogin() : "")
                    .encode());
        }

        LOG.infof("Ask chat WebSocket connected: %s user=%s", connectionId, user.getUserName());

        webSocket.textMessageHandler(message -> {
            try {
                JsonObject msgJson = new JsonObject(message);
                String action = msgJson.getString("action");
                switch (action) {
                    case "sendMessage" -> handleUserMessage(webSocket, msgJson, connectionId, userHolder);
                    case "getHistory" -> handleGetHistory(webSocket, msgJson, connectionId, userHolder);
                    default -> sendError(webSocket, "Unknown action: " + action);
                }
            } catch (Exception e) {
                LOG.error("Error processing ask message", e);
                sendError(webSocket, "Invalid message format: " + e.getMessage());
            }
        });

        webSocket.closeHandler(v -> {
            IUser closingUser = userHolder.getUser();
            if (!isAnonymous(closingUser)) {
                assert askChatService != null;
                askChatService.persistConnectionHistory(connectionId, closingUser.getId());
            }
            activeConnections.remove(connectionId);
            connectionUsers.remove(connectionId);
            LOG.infof("Ask chat WebSocket closed: %s", connectionId);
        });

        webSocket.exceptionHandler(err -> {
            LOG.errorf(err, "Ask WebSocket error for %s", connectionId);
            activeConnections.remove(connectionId);
            connectionUsers.remove(connectionId);
        });
    }

    private void handleUserMessage(ServerWebSocket webSocket, JsonObject msgJson, String connectionId,
                                   UserHolder userHolder) {
        IUser user = userHolder.getUser();
        String content = msgJson.getString("content");
        if (content == null || content.trim().isEmpty()) {
            sendError(webSocket, "Message content cannot be empty");
            return;
        }
        if (content.length() > 2000) {
            sendError(webSocket, "Message too long (max 2000 characters)");
            return;
        }

        String username = isAnonymous(user)
                ? sanitizeUsername(msgJson.getString("username", "anonymous"))
                : (user.getLogin() != null ? user.getLogin() : "user");

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
                                    userHolder.getUser()
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

    private void handleGetHistory(ServerWebSocket webSocket, JsonObject msgJson, String connectionId,
                                  UserHolder userHolder) {
        Integer limit = msgJson.getInteger("limit", 50);
        assert askChatService != null;
        askChatService.getChatHistory(limit, connectionId, userHolder.getUser())
                .subscribe().with(
                        webSocket::writeTextMessage,
                        err -> {
                            LOG.error("Error getting ask chat history", err);
                            sendError(webSocket, err.getMessage());
                        }
                );
    }

    public void upgradeUserSession(String connectionId, IUser newUser) {
        UserHolder holder = connectionUsers.get(connectionId);
        if (holder != null) {
            holder.setUser(newUser);
            LOG.infof("Ask session upgraded connection=%s user=%s", connectionId, newUser.getUserName());
        }
    }

    public void downgradeUserSession(String connectionId) {
        UserHolder holder = connectionUsers.get(connectionId);
        if (holder != null) {
            holder.setUser(AnonymousUser.build());
            LOG.infof("Ask session downgraded connection=%s", connectionId);
        }
    }

    public void sendToConnection(String connectionId, String message) {
        ServerWebSocket ws = activeConnections.get(connectionId);
        if (ws != null && !ws.isClosed()) {
            ws.writeTextMessage(message);
        }
    }

    private boolean isAnonymous(IUser user) {
        return user instanceof AnonymousUser || user.getId() == 0;
    }

    private boolean isValidAnonId(String anonId) {
        if (anonId == null || anonId.isBlank()) return false;
        if (anonId.length() == 16 && anonId.chars().allMatch(AskChatController::isBase64UrlChar)) {
            return true;
        }
        try {
            java.util.UUID.fromString(anonId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBase64UrlChar(int c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '_';
    }

    private static String newConnectionId() {
        byte[] buf = new byte[12];
        CONNECTION_ID_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sanitizeUsername(String raw) {
        if (raw == null || raw.isBlank()) return "anonymous";
        String cleaned = raw.replaceAll("[\\r\\n\\t\\x00-\\x1F\\x7F]", "").trim();
        return cleaned.isEmpty() ? "anonymous" : cleaned.substring(0, Math.min(cleaned.length(), 64));
    }

    private void sendError(ServerWebSocket webSocket, String message) {
        webSocket.writeTextMessage(ChatMessageDTO.error(message, "system", "system").build().toJson());
    }
}
