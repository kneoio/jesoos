package com.semantyca.jesoos.external;

import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

@ApplicationScoped
public class KeycloakAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAuthService.class);

    @Inject
    Vertx vertx;

    @Inject
    ReactiveMailer mailer;

    @Inject
    PublicChatSessionManager sessionManager;

    @Inject
    JesoosConfig config;

    private static final SecureRandom RANDOM = new SecureRandom();
    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    private Uni<String> getAdminToken() {
        String url = config.keycloak().getUrl() + "/realms/" + config.keycloak().getRealm() + "/protocol/openid-connect/token";
        MultiMap form = MultiMap.caseInsensitiveMultiMap()
                .add("grant_type", "client_credentials")
                .add("client_id", config.keycloak().getClientId())
                .add("client_secret", config.keycloak().getClientSecret());

        return webClient.postAbs(url)
                .sendForm(form)
                .map(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Failed to get admin token: HTTP " + resp.statusCode());
                    }
                    return resp.bodyAsJsonObject().getString("access_token");
                });
    }

    private Uni<String> findOrCreateUser(String email, String adminToken) {
        String searchUrl = config.keycloak().getUrl() + "/admin/realms/" + config.keycloak().getRealm()
                + "/users?email=" + email + "&exact=true";

        return webClient.getAbs(searchUrl)
                .putHeader("Authorization", "Bearer " + adminToken)
                .send()
                .flatMap(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("User search failed: HTTP " + resp.statusCode()
                                + " body: " + resp.bodyAsString());
                    }
                    String rawBody = resp.bodyAsString();
                    LOG.debug("Keycloak user search response ({}): {}", resp.statusCode(), rawBody);
                    JsonArray users;
                    try {
                        users = new JsonArray(rawBody);
                    } catch (Exception e) {
                        throw new RuntimeException("User search returned non-array: " + rawBody, e);
                    }
                    if (!users.isEmpty()) {
                        String userId = users.getJsonObject(0).getString("id");
                        LOG.info("Found existing Keycloak user {} for email {}", userId, email);
                        return Uni.createFrom().item(userId);
                    }

                    LOG.info("User not found, creating Keycloak account for {}", email);
                    JsonObject newUser = new JsonObject()
                            .put("username", email)
                            .put("email", email)
                            .put("emailVerified", false)
                            .put("enabled", true);

                    String createUrl = config.keycloak().getUrl() + "/admin/realms/" + config.keycloak().getRealm() + "/users";
                    return webClient.postAbs(createUrl)
                            .putHeader("Authorization", "Bearer " + adminToken)
                            .putHeader("Content-Type", "application/json")
                            .sendJsonObject(newUser)
                            .map(createResp -> {
                                if (createResp.statusCode() != 201) {
                                    throw new RuntimeException(
                                            "Failed to create user: HTTP " + createResp.statusCode()
                                            + " " + createResp.bodyAsString());
                                }
                                String location = createResp.getHeader("Location");
                                String userId = location.substring(location.lastIndexOf('/') + 1);
                                LOG.info("Created Keycloak user {} for email {}", userId, email);
                                return userId;
                            });
                });
    }

    public Uni<KeycloakAuthResult> verifyAuth(String email, String code) {
        return Uni.createFrom().item(() -> {
            boolean valid = sessionManager.verifyAndConsumePendingOtp(email, code);
            if (!valid) {
                return new KeycloakAuthResult(false, "Invalid or expired code");
            }
            String token = java.util.UUID.randomUUID().toString();
            sessionManager.storeUserToken(token, email);
            return new KeycloakAuthResult(true, token);
        });
    }

    public Uni<Boolean> startAuth(String email) {
        return getAdminToken()
                .flatMap(adminToken -> findOrCreateUser(email, adminToken))
                .flatMap(userId -> {
                    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
                    sessionManager.storePendingOtp(email, code);
                    LOG.info("OTP generated and stored for {}", email);

                    String htmlBody = "<!DOCTYPE html>"
                            + "<html><head><style>"
                            + "body { font-family: Arial, sans-serif; background-color: #f4f4f4; margin: 0; padding: 20px; }"
                            + ".container { max-width: 600px; margin: 0 auto; background-color: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }"
                            + ".header { text-align: center; margin-bottom: 30px; }"
                            + ".logo { font-size: 28px; font-weight: bold; color: #2c3e50; margin-bottom: 10px; }"
                            + ".code-box { background-color: #f8f9fa; border: 2px dashed #007bff; padding: 20px; text-align: center; margin: 30px 0; border-radius: 8px; }"
                            + ".code { font-size: 32px; font-weight: bold; color: #007bff; letter-spacing: 5px; font-family: 'Courier New', monospace; }"
                            + ".info { color: #6c757d; font-size: 14px; text-align: center; margin-top: 20px; }"
                            + ".footer { margin-top: 30px; padding-top: 20px; border-top: 1px solid #dee2e6; font-size: 12px; color: #6c757d; text-align: center; }"
                            + "</style></head><body>"
                            + "<div class='container'>"
                            + "<div class='header'>"
                            + "<div class='logo'>Mixpla</div>"
                            + "<h2>Verification Code</h2>"
                            + "</div>"
                            + "<p>Hello,</p>"
                            + "<p>Please use the following verification code to complete your authentication:</p>"
                            + "<div class='code-box'>"
                            + "<div class='code'>" + code + "</div>"
                            + "</div>"
                            + "<div class='info'>"
                            + "<p>This code will expire in 10 minutes.</p>"
                            + "<p>For your security, please do not share this code with anyone.</p>"
                            + "</div>"
                            + "<div class='footer'>"
                            + "<p>If you didn't request this code, you can safely ignore this email.</p>"
                            + "<p>&copy; 2024 Mixpla. All rights reserved.</p>"
                            + "</div>"
                            + "</div></body></html>";

                    Mail mail = Mail.withHtml(
                            email,
                            "Your Mixpla verification code",
                            htmlBody
                    ).setFrom(config.getFromAddress());

                    return mailer.send(mail)
                            .map(v -> {
                                LOG.info("OTP email sent to {}", email);
                                return true;
                            });
                });
    }
}
