package com.semantyca.jesoos.test;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class UploadFlowTest {

    private static final String BASE_URL   = System.getProperty("base.url", "http://localhost:38797");
    private static final String WS_URL     = BASE_URL.replace("http", "ws");
    private static final String BRAND_SLUG = "lumisonic";
    private static final String TEST_EMAIL = "test@mixpla.io";
    private static final Path   TEST_AUDIO = Paths.get(System.getProperty("project.basedir",
            "/home/aidazi/IdeaProjects/jesoos"), "src/test/resources/Waiting_State.wav");

    @Test
    void uploadFlow_authenticatedUser_uploadsSongSuccessfully() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        // ── Step 1: authenticate ──────────────────────────────────────────────
        String sessionToken = authenticate(http);
        assertNotNull(sessionToken, "Must receive session token from auth flow");

        // ── Step 2: connect WS as authenticated user ──────────────────────────
        CopyOnWriteArrayList<JsonObject> received = new CopyOnWriteArrayList<>();
        StringBuilder buf = new StringBuilder();

        WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(WS_URL + "/jesoos/ws/chat?token=" + sessionToken),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                buf.append(data);
                                if (last) {
                                    try { received.add(new JsonObject(buf.toString())); }
                                    catch (Exception ignored) {}
                                    buf.setLength(0);
                                }
                                ws.request(1);
                                return null;
                            }
                        })
                .get(10, TimeUnit.SECONDS);
        ws.request(1);

        try {
            // ── Step 3: express upload intent ─────────────────────────────────
            send(ws, "I want to upload my song");

            // ── Step 4: handle optional artist registration, assert show_upload_button ──
            JsonObject commandMsg = waitForUploadButton(ws, received, 90);
            assertNotNull(commandMsg, "COMMAND show_upload_button must be received");
            assertEquals("show_upload_button", commandMsg.getString("content"));
            System.out.println("[test] show_upload_button received");

            // ── Step 5: upload audio file ─────────────────────────────────────
            String filename = uploadFile(http, sessionToken);
            System.out.println("[test] temp file uploaded: " + filename);

            // ── Step 6: tell bot about the file with all metadata ─────────────
            send(ws, "I uploaded a file: " + filename
                    + ". Title: Waiting State. Artist: Test Artist. Genre: Electronic");

            // ── Step 7: assert bot confirms the track is saved ────────────────
            JsonObject successMsg = waitFor(received,
                    msg -> isBotMessage(msg) && contentIncludes(msg,
                            "saved", "queue", "broadcast", "catalog", "added", "on air", "contribution", "uploaded", "live"),
                    90);
            System.out.println("[test] messages after upload:");
            received.forEach(m -> System.out.println("  >> " + m.encode()));
            assertNotNull(successMsg, "Bot must confirm successful upload");
            System.out.println("[test] Upload confirmed: " + botContent(successMsg));

        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        }
    }

    // ── auth helper (mirrors AuthFlowTest) ───────────────────────────────────

    private String authenticate(HttpClient http) throws Exception {
        CopyOnWriteArrayList<JsonObject> received = new CopyOnWriteArrayList<>();
        StringBuilder buf = new StringBuilder();

        WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(WS_URL + "/jesoos/ws/chat"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            try { received.add(new JsonObject(buf.toString())); }
                            catch (Exception ignored) {}
                            buf.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);
        ws.request(1);

        try {
            send(ws, "I want to login. My email is " + TEST_EMAIL);

            waitFor(received,
                    msg -> isBotMessage(msg) && contentIncludes(msg, "verification", "code", "sent", "check your"),
                    60);

            System.out.println("[test] messages after login intent:");
            received.forEach(m -> System.out.println("  >> " + m.encode()));

            String otp = fetchOtp(http, TEST_EMAIL, 15, 2000);

            send(ws, "My verification code is " + otp);

            JsonObject sessionMsg = waitFor(received,
                    msg -> "session_token".equals(msg.getString("type")),
                    60);

            assertNotNull(sessionMsg, "session_token must arrive");
            return sessionMsg.getString("token");

        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        }
    }

    // ── upload button waiter — handles optional artist registration dialog ───

    private JsonObject waitForUploadButton(WebSocket ws, CopyOnWriteArrayList<JsonObject> received,
                                           int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        boolean repliedYes = false;

        while (System.currentTimeMillis() < deadline) {
            JsonObject cmd = received.stream()
                    .filter(m -> "COMMAND".equals(m.getString("type"))
                            && "show_upload_button".equals(m.getString("content")))
                    .findFirst().orElse(null);
            if (cmd != null) return cmd;

            if (!repliedYes) {
                boolean botAsksArtist = received.stream().anyMatch(
                        m -> isBotMessage(m) && contentIncludes(m, "artist", "register", "label"));
                if (botAsksArtist) {
                    try { send(ws, "yes"); } catch (Exception ignored) {}
                    repliedYes = true;
                }
            }

            Thread.sleep(300);
        }
        return null;
    }

    // ── multipart file upload ────────────────────────────────────────────────

    private String uploadFile(HttpClient http, String token) throws Exception {
        byte[] fileBytes = Files.readAllBytes(TEST_AUDIO);
        String boundary = UUID.randomUUID().toString().replace("-", "");
        String filename  = TEST_AUDIO.getFileName().toString();

        byte[] body = buildMultipart(boundary, filename, fileBytes);

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/jesoos/chat/upload-temp?token=" + token))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(), "File upload must succeed: " + resp.body());
        return new JsonObject(resp.body()).getString("filename");
    }

    private byte[] buildMultipart(String boundary, String filename, byte[] fileBytes) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        byte[] h = header.getBytes();
        byte[] f = footer.getBytes();
        byte[] result = new byte[h.length + fileBytes.length + f.length];
        System.arraycopy(h, 0, result, 0, h.length);
        System.arraycopy(fileBytes, 0, result, h.length, fileBytes.length);
        System.arraycopy(f, 0, result, h.length + fileBytes.length, f.length);
        return result;
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    private void send(WebSocket ws, String content) throws Exception {
        String json = new JsonObject()
                .put("action", "sendMessage")
                .put("content", content)
                .put("brandSlug", BRAND_SLUG)
                .put("username", "testmixpla.io")
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

    private String fetchOtp(HttpClient http, String email, int retries, long delayMs) throws Exception {
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

    private String botContent(JsonObject msg) {
        return msg.getJsonObject("data", new JsonObject()).getString("content", "");
    }
}
