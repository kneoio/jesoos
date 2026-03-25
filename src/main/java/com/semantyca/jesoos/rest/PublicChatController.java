package com.semantyca.jesoos.rest;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.UUID.randomUUID;

@ApplicationScoped
public class PublicChatController extends AbstractSecuredController<Object, Object> {
    private static final Logger LOG = LoggerFactory.getLogger(PublicChatController.class);
    private final PublicChatService publicChatService;
    private final Map<String, ServerWebSocket> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userStationRegistrations = new ConcurrentHashMap<>();

    public PublicChatController() {
        super(null);
        this.publicChatService = null;
    }

    @Inject
    public PublicChatController(UserService userService, PublicChatService publicChatService) {
        super(userService);
        this.publicChatService = publicChatService;
    }

    public void setupRoutes(Router router) {
        String path = "/jesoos/chat";
        router.route(path + "*").handler(BodyHandler.create());
        router.route(path + "*").handler(this::addHeaders);
        
        router.route("/jesoos/ws/chat").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                String token = rc.request().getParam("token");
                LOG.info("WebSocket connection attempt with token: {}", token);
                
                authenticateUserFromToken(token)
                        .subscribe().with(
                                user -> {
                                    LOG.info("User authenticated: {}", user.getUserName());
                                    rc.request().toWebSocket().onSuccess(ws -> handlePublicChatWebSocket(ws, user))
                                            .onFailure(err -> {
                                                LOG.error("WebSocket connection failed", err);
                                                rc.fail(500, err);
                                            });
                                },
                                err -> {
                                    LOG.warn("Authentication failed for token: {}", token);
                                    rc.response().setStatusCode(401).end("Invalid or expired token");
                                }
                        );
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });
    }

    private Uni<IUser> authenticateUserFromToken(String token) {
        if ("test-token".equals(token)) {
            return getUserService().findByEmail("test@example.com")
                    .onItem().transform(user -> {
                        if (user == null || user.getId() == 0) {
                            return AnonymousUser.build();
                        }
                        return user;
                    });
        }
        return Uni.createFrom().failure(new IllegalArgumentException("Invalid token"));
    }

    private void handlePublicChatWebSocket(ServerWebSocket webSocket, IUser user) {
        webSocket.accept();
        
        String connectionId = randomUUID().toString();
        activeConnections.put(connectionId, webSocket);
        LOG.info("Public chat WebSocket connected: {} for user: {}", connectionId, user.getUserName());

        webSocket.textMessageHandler(message -> {
            try {
                JsonObject msgJson = new JsonObject(message);
                String action = msgJson.getString("action");
                String brandSlug = msgJson.getString("brandSlug");
                
                switch (action) {
                    case "sendMessage":
                        handleUserMessage(webSocket, msgJson, connectionId, brandSlug, user);
                        break;
                    case "getHistory":
                        handleGetHistory(webSocket, msgJson, user);
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
            LOG.info("Public chat WebSocket closed: {}", connectionId);
        });

        webSocket.exceptionHandler(err -> {
            LOG.error("WebSocket error for {}", connectionId, err);
            activeConnections.remove(connectionId);
            userStationRegistrations.remove(connectionId);
        });
    }

    private void handleUserMessage(ServerWebSocket webSocket, JsonObject msgJson, String connectionId, 
                                  String brandSlug, IUser user) {
        String username = msgJson.getString("username", user.getUserName());
        String content = msgJson.getString("content");

        if (content == null || content.trim().isEmpty()) {
            sendError(webSocket, "Message content cannot be empty");
            return;
        }

        assert publicChatService != null;
        
        Set<String> registeredStations = userStationRegistrations.computeIfAbsent(connectionId, k -> ConcurrentHashMap.newKeySet());
        
        Uni<Void> ensureRegistration;
        if (!registeredStations.contains(brandSlug)) {
            ensureRegistration = publicChatService.ensureUserIsListenerOfStation(user.getId(), brandSlug)
                    .invoke(() -> registeredStations.add(brandSlug));
        } else {
            ensureRegistration = Uni.createFrom().voidItem();
        }
        
        ensureRegistration
                .chain(() -> publicChatService.processUserMessage(username, content, connectionId, brandSlug, user))
                .subscribe().with(
                        response -> {
                            webSocket.writeTextMessage(response);
                            sendBotResponse(webSocket, content, connectionId, brandSlug, user);
                        },
                        err -> {
                            LOG.error("Error processing user message", err);
                            sendError(webSocket, err);
                        }
                );
    }

    private void sendBotResponse(ServerWebSocket webSocket, String userMessage, String connectionId, 
                                String brandSlug, IUser user) {
        assert publicChatService != null;
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

    private void handleGetHistory(ServerWebSocket webSocket, JsonObject msgJson, IUser user) {
        String brandSlug = msgJson.getString("brandSlug");
        Integer limit = msgJson.getInteger("limit", 50);

        assert publicChatService != null;
        publicChatService.getChatHistory(brandSlug, limit, user)
                .subscribe().with(
                        webSocket::writeTextMessage,
                        err -> {
                            LOG.error("Error getting chat history", err);
                            sendError(webSocket, err);
                        }
                );
    }

    private void sendError(ServerWebSocket webSocket, Throwable err) {
        sendError(webSocket, err.getMessage());
    }

    private void sendError(ServerWebSocket webSocket, String message) {
        webSocket.writeTextMessage(ChatMessageDTO.error(message, "system", "system").build().toJson());
    }
}