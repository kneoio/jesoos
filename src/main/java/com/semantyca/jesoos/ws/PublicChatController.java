package com.semantyca.jesoos.ws;

import com.semantyca.core.controller.AbstractSecuredController;
import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.service.chat.PublicChatService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;


import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PublicChatController extends AbstractSecuredController<Object, Object> {
    private static final Logger LOG = Logger.getLogger(PublicChatController.class);
    private static final SecureRandom CONNECTION_ID_RANDOM = new SecureRandom();
    private final PublicChatService publicChatService;
    private final Map<String, ServerWebSocket> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userStationRegistrations = new ConcurrentHashMap<>();
    private final Map<String, UserHolder> connectionUsers = new ConcurrentHashMap<>();

    public static class UserHolder {
        private volatile IUser user;
        
        public UserHolder(IUser user) {
            this.user = user;
        }
        
        public IUser getUser() {
            return user;
        }
        
        public void setUser(IUser user) {
            this.user = user;
        }
    }

    public PublicChatController() {
        super(null);
        this.publicChatService = null;
    }

    @Inject
    public PublicChatController(UserService userService, PublicChatService publicChatService) {
        super(userService);
        this.publicChatService = publicChatService;
        if (publicChatService != null) {
            publicChatService.setController(this);
        }
    }

    public void setupRoutes(Router router) {
        String path = "/jesoos/chat";
        router.route(path + "*").handler(BodyHandler.create().setBodyLimit(65536));
        router.route(path + "*").handler(this::addHeaders);
        
        router.route("/jesoos/ws/chat").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                String token = rc.request().getParam("token");
                LOG.infof("WebSocket connection attempt with token: %s", token);
                
                String anonId = rc.request().getParam("anonId");
                authenticateUserFromToken(token)
                        .subscribe().with(
                                user -> {
                                    LOG.infof("User authenticated: %s", user.getUserName());
                                    rc.request().toWebSocket().onSuccess(ws -> handlePublicChatWebSocket(ws, user, anonId))
                                            .onFailure(err -> {
                                                LOG.error("WebSocket connection failed", err);
                                                rc.fail(500, err);
                                            });
                                },
                                err -> {
                                    LOG.warnf("Authentication failed for token: %s", token);
                                    rc.response().setStatusCode(401).end("Invalid or expired token");
                                }
                        );
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });
    }

    private Uni<IUser> authenticateUserFromToken(String token) {
        if (token == null || token.isBlank()) {
            LOG.infof("[ws-auth] no token — anonymous");
            return Uni.createFrom().item(AnonymousUser.build());
        }
        assert publicChatService != null;
        return publicChatService.authenticateUserFromToken(token)
                .onItem().invoke(user -> {
                    if (user instanceof AnonymousUser || user.getId() == 0) {
                        LOG.warnf("[ws-auth] token present but resolved to anonymous — token may be expired or invalidated: %s", token.substring(0, 8) + "...");
                    } else {
                        LOG.infof("[ws-auth] token OK — userId=%d email=%s", user.getId(), user.getEmail());
                    }
                })
                .onFailure().recoverWithItem(err -> {
                    LOG.warnf("[ws-auth] token validation failed — %s", err.getMessage());
                    return AnonymousUser.build();
                });
    }

    private void handlePublicChatWebSocket(ServerWebSocket webSocket, IUser user, String anonId) {
        webSocket.accept();

        String connectionId = (isAnonymous(user) && isValidAnonId(anonId))
                ? anonId
                : newConnectionId();
        activeConnections.put(connectionId, webSocket);
        UserHolder userHolder = new UserHolder(user);
        connectionUsers.put(connectionId, userHolder);
        LOG.infof("Public chat WebSocket connected: %s for user: %s", connectionId, user.getUserName());

        webSocket.textMessageHandler(message -> {
            try {
                JsonObject msgJson = new JsonObject(message);
                String action = msgJson.getString("action");
                String brandSlug = msgJson.getString("brandSlug");
                
                switch (action) {
                    case "sendMessage":
                        handleUserMessage(webSocket, msgJson, connectionId, brandSlug, userHolder);
                        break;
                    case "getHistory":
                        handleGetHistory(webSocket, msgJson, connectionId, userHolder);
                        break;
                    default:
                        sendError(webSocket, "Unknown action: " + action);
                }
            } catch (Exception e) {
                LOG.error("Error processing message", e);
                sendError(webSocket, "Invalid message format: " + e.getMessage());
            }
        });

        webSocket.closeHandler(v -> {
            activeConnections.remove(connectionId);
            userStationRegistrations.remove(connectionId);
            connectionUsers.remove(connectionId);
            LOG.infof("Public chat WebSocket closed: %s", connectionId);
        });

        webSocket.exceptionHandler(err -> {
            LOG.error("WebSocket error for %s", connectionId, err);
            activeConnections.remove(connectionId);
            userStationRegistrations.remove(connectionId);
            connectionUsers.remove(connectionId);
        });
    }

    private void handleUserMessage(ServerWebSocket webSocket, JsonObject msgJson, String connectionId, 
                                  String brandSlug, UserHolder userHolder) {
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

        Set<String> registeredStations = userStationRegistrations.computeIfAbsent(connectionId, k -> ConcurrentHashMap.newKeySet());

        Uni<Void> ensureRegistration;
        if (!isAnonymous(user) && !registeredStations.contains(brandSlug)) {
            assert publicChatService != null;
            ensureRegistration = publicChatService.ensureUserIsListenerOfStation(user.getId(), brandSlug)
                    .invoke(() -> registeredStations.add(brandSlug));
        } else {
            ensureRegistration = Uni.createFrom().voidItem();
        }

        Uni<String> resolvedUsername = isAnonymous(user)
                ? Uni.createFrom().item(sanitizeUsername(msgJson.getString("username", "anonymous")))
                : publicChatService.resolveDisplayName(user.getId(), user.getEmail());

        ensureRegistration
                .chain(() -> resolvedUsername)
                .chain(username -> publicChatService.processUserMessage(username, content, connectionId, brandSlug, user))
                .subscribe().with(
                        response -> {
                            webSocket.writeTextMessage(response);
                            webSocket.writeTextMessage(ChatMessageDTO.processing("...", connectionId).build().toJson());
                            sendBotResponse(webSocket, content, connectionId, brandSlug, userHolder);
                        },
                        err -> {
                            LOG.error("Error processing user message", err);
                            sendError(webSocket, err);
                        }
                );
    }

    private void sendBotResponse(ServerWebSocket webSocket, String userMessage, String connectionId, 
                                String brandSlug, UserHolder userHolder) {
        IUser user = userHolder.getUser();
        publicChatService.generateBotResponse(
                userMessage,
                webSocket::writeTextMessage,
                webSocket::writeTextMessage,
                connectionId,
                brandSlug,
                user
        ).subscribe().with(
                v -> {},
                e -> {
                    LOG.error("Bot response error", e);
                    sendError(webSocket, "Bot response failed: " + e.getMessage());
                }
        );
    }

    private void handleGetHistory(ServerWebSocket webSocket, JsonObject msgJson, String connectionId, UserHolder userHolder) {
        String brandSlug = msgJson.getString("brandSlug");
        Integer limit = msgJson.getInteger("limit", 50);

        IUser user = userHolder.getUser();
        publicChatService.getChatHistory(brandSlug, limit, connectionId, user)
                .subscribe().with(
                        webSocket::writeTextMessage,
                        err -> {
                            LOG.error("Error getting chat history", err);
                            sendError(webSocket, err);
                        }
                );
    }

    private boolean isAnonymous(IUser user) {
        return user instanceof AnonymousUser || user.getId() == 0;
    }

    private boolean isValidAnonId(String anonId) {
        if (anonId == null || anonId.isBlank()) return false;
        if (anonId.length() == 16 && anonId.chars().allMatch(PublicChatController::isBase64UrlChar)) {
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

    public void upgradeUserSession(String connectionId, IUser newUser) {
        UserHolder holder = connectionUsers.get(connectionId);
        if (holder != null && holder.getUser().getId() == 0) {
            assert publicChatService != null;
            publicChatService.migrateAnonymousSession(connectionId, newUser.getId())
                    .subscribe().with(
                            v -> LOG.infof("Migrated anonymous chat for connection %s to user %s", connectionId, newUser.getUserName()),
                            e -> LOG.errorf(e, "Failed to migrate anonymous chat for connection %s", connectionId)
                    );
        }
        if (holder != null) {
            holder.setUser(newUser);
            LOG.infof("Upgraded user session for connection %s to user %s", connectionId, newUser.getUserName());
        }
    }

    public void downgradeUserSession(String connectionId) {
        UserHolder holder = connectionUsers.get(connectionId);
        if (holder != null) {
            holder.setUser(AnonymousUser.build());
            LOG.infof("Downgraded user session for connection %s to anonymous", connectionId);
        }
    }

    public void sendToConnection(String connectionId, String message) {
        ServerWebSocket ws = activeConnections.get(connectionId);
        if (ws != null && !ws.isClosed()) {
            ws.writeTextMessage(message);
        }
    }

    private static String sanitizeUsername(String raw) {
        if (raw == null || raw.isBlank()) return "anonymous";
        String cleaned = raw.replaceAll("[\\r\\n\\t\\x00-\\x1F\\x7F]", "").trim();
        return cleaned.isEmpty() ? "anonymous" : cleaned.substring(0, Math.min(cleaned.length(), 64));
    }

    private void sendError(ServerWebSocket webSocket, Throwable err) {
        sendError(webSocket, err.getMessage());
    }

    private void sendError(ServerWebSocket webSocket, String message) {
        webSocket.writeTextMessage(ChatMessageDTO.error(message, "system", "system").build().toJson());
    }
}