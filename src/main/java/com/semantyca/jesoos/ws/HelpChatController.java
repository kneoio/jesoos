package com.semantyca.jesoos.ws;

import com.semantyca.jesoos.dto.ChatMessageDTO;
import com.semantyca.jesoos.service.help.HelpChatService;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public Mixpla help WebSocket — no authentication, no brand, knowledge answers only.
 * Isolated from {@link PublicChatController} (in-player brand chat) and {@link AskChatController}.
 */
@ApplicationScoped
public class HelpChatController {

    private static final Logger LOG = Logger.getLogger(HelpChatController.class);
    private static final SecureRandom CONNECTION_ID_RANDOM = new SecureRandom();
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int MAX_MESSAGES_PER_WINDOW = 15;
    private static final long RATE_WINDOW_MS = 60_000L;

    private final HelpChatService helpChatService;
    private final Map<String, ServerWebSocket> activeConnections = new ConcurrentHashMap<>();

    public HelpChatController() {
        this.helpChatService = null;
    }

    @Inject
    public HelpChatController(HelpChatService helpChatService) {
        this.helpChatService = helpChatService;
        if (helpChatService != null) {
            helpChatService.setController(this);
        }
    }

    public void setupRoutes(Router router) {
        router.route("/jesoos/ws/help").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                rc.request().toWebSocket()
                        .onSuccess(this::handleHelpWebSocket)
                        .onFailure(err -> {
                            LOG.error("Help WebSocket connection failed", err);
                            rc.fail(500, err);
                        });
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });
    }

    private void handleHelpWebSocket(ServerWebSocket webSocket) {
        webSocket.accept();

        String connectionId = newConnectionId();
        activeConnections.put(connectionId, webSocket);
        RateLimiter rateLimiter = new RateLimiter();

        LOG.infof("Help chat WebSocket connected: %s", connectionId);

        webSocket.textMessageHandler(message -> {
            try {
                JsonObject msgJson = new JsonObject(message);
                String action = msgJson.getString("action");
                switch (action) {
                    case "sendMessage" -> handleUserMessage(webSocket, msgJson, connectionId, rateLimiter);
                    case "getHistory" -> handleGetHistory(webSocket, msgJson, connectionId);
                    default -> sendError(webSocket, "Unknown action: " + action);
                }
            } catch (Exception e) {
                LOG.error("Error processing help message", e);
                sendError(webSocket, "Invalid message format");
            }
        });

        webSocket.closeHandler(v -> {
            helpChatService.dropConversation(connectionId);
            activeConnections.remove(connectionId);
            LOG.infof("Help chat WebSocket closed: %s", connectionId);
        });

        webSocket.exceptionHandler(err -> {
            LOG.errorf(err, "Help WebSocket error for %s", connectionId);
            helpChatService.dropConversation(connectionId);
            activeConnections.remove(connectionId);
        });
    }

    private void handleUserMessage(ServerWebSocket webSocket, JsonObject msgJson, String connectionId,
                                   RateLimiter rateLimiter) {
        String content = msgJson.getString("content");
        if (content == null || content.trim().isEmpty()) {
            sendError(webSocket, "Message content cannot be empty");
            return;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            sendError(webSocket, "Message too long (max " + MAX_CONTENT_LENGTH + " characters)");
            return;
        }
        if (!rateLimiter.allow()) {
            LOG.warnf("[help] rate limit hit connection=%s", connectionId);
            sendError(webSocket, "Too many messages — please wait a moment.");
            return;
        }

        helpChatService.processUserMessage(content, connectionId)
                .subscribe().with(
                        response -> {
                            webSocket.writeTextMessage(response);
                            webSocket.writeTextMessage(ChatMessageDTO.processing("...", connectionId).build().toJson());
                            helpChatService.generateBotResponse(
                                    content,
                                    webSocket::writeTextMessage,
                                    webSocket::writeTextMessage,
                                    connectionId
                            ).subscribe().with(
                                    v -> {},
                                    e -> {
                                        LOG.error("Help bot response error", e);
                                        sendError(webSocket, "Bot response failed");
                                    }
                            );
                        },
                        err -> {
                            LOG.error("Error processing help user message", err);
                            sendError(webSocket, "Could not process message");
                        }
                );
    }

    private void handleGetHistory(ServerWebSocket webSocket, JsonObject msgJson, String connectionId) {
        Integer limit = msgJson.getInteger("limit", 50);
        helpChatService.getChatHistory(limit, connectionId)
                .subscribe().with(
                        webSocket::writeTextMessage,
                        err -> {
                            LOG.error("Error getting help chat history", err);
                            sendError(webSocket, "Could not load history");
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

    /** Per-connection sliding window; this endpoint is open to the internet. */
    private static final class RateLimiter {
        private final AtomicInteger count = new AtomicInteger();
        private volatile long windowStart = System.currentTimeMillis();

        boolean allow() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= RATE_WINDOW_MS) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= MAX_MESSAGES_PER_WINDOW;
        }
    }
}
