package com.semantyca.jesoos.test;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class AuthFlowTest {

    private static final String BASE_URL   = System.getProperty("base.url", "http://localhost:38797");
    private static final String WS_URL     = BASE_URL.replace("http", "ws");
    private static final String BRAND_SLUG = "lumisonic";
    private static final String TEST_EMAIL = "test@mixpla.io";

    @Test
    void authFlow_expressLoginIntent_receivesSessionToken() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        CopyOnWriteArrayList<JsonObject> received = new CopyOnWriteArrayList<>();
        StringBuilder textBuffer = new StringBuilder();

        WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(WS_URL + "/jesoos/ws/chat"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        textBuffer.append(data);
                        if (last) {
                            try { received.add(new JsonObject(textBuffer.toString())); }
                            catch (Exception ignored) {}
                            textBuffer.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        ws.request(1);

        try {
            // ── Step 1: express login intent with email ───────────────────────
            send(ws, "I want to login. My email is " + TEST_EMAIL);

            // Wait for bot to confirm OTP was sent
            JsonObject otpSentMsg = waitFor(received,
                    msg -> isBotMessage(msg) && contentIncludes(msg, "verification", "code", "sent", "check your"),
                    60);
            assertNotNull(otpSentMsg, "Bot should confirm OTP was sent");

            // ── Step 2: intercept OTP via dev endpoint ────────────────────────
            String otp = fetchOtp(http, TEST_EMAIL, 15, 2000);
            System.out.println("[test] OTP intercepted: " + otp);

            // ── Step 3: submit OTP through chat ───────────────────────────────
            send(ws, "My verification code is " + otp);

            // ── Step 4: wait for session_token ────────────────────────────────
            JsonObject sessionMsg = waitFor(received,
                    msg -> "session_token".equals(msg.getString("type")),
                    60);

            // ── Assertions ────────────────────────────────────────────────────
            assertNotNull(sessionMsg, "session_token message must arrive");
            assertFalse(sessionMsg.getString("token", "").isBlank(), "token must be non-empty");
            assertFalse(sessionMsg.getString("userName", "").isBlank(), "userName must be non-empty");

            System.out.println("[test] Auth success — userName=" + sessionMsg.getString("userName"));

        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void send(WebSocket ws, String content) throws Exception {
        String json = new JsonObject()
                .put("action", "sendMessage")
                .put("content", content)
                .put("brandSlug", BRAND_SLUG)
                .put("username", "anonymous")
                .encode();
        ws.sendText(json, true).get(5, TimeUnit.SECONDS);
    }

    private JsonObject waitFor(CopyOnWriteArrayList<JsonObject> messages,
                                Predicate<JsonObject> pred,
                                int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (JsonObject m : messages) {
                if (pred.test(m)) return m;
            }
            Thread.sleep(200);
        }
        return null;
    }

    private String fetchOtp(HttpClient http, String email, int retries, long delayMs)
            throws Exception {
        String url = BASE_URL + "/jesoos/dev/pending-otp?email=" + email;
        for (int i = 0; i < retries; i++) {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject body = new JsonObject(resp.body());
                if (Boolean.TRUE.equals(body.getBoolean("found")) && !body.getString("otp", "").isBlank()) {
                    return body.getString("otp");
                }
            }
            Thread.sleep(delayMs);
        }
        throw new AssertionError("OTP not found for " + email + " after " + retries + " retries");
    }

    private boolean isBotMessage(JsonObject msg) {
        return "message".equals(msg.getString("type"))
                && "BOT".equals(msg.getJsonObject("data", new JsonObject()).getString("type"));
    }

    private boolean contentIncludes(JsonObject msg, String... terms) {
        String content = msg.getJsonObject("data", new JsonObject())
                .getString("content", "").toLowerCase();
        for (String t : terms) {
            if (content.contains(t.toLowerCase())) return true;
        }
        return false;
    }
}
