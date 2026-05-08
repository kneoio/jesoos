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
import java.util.Locale;

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
        String normalizedEmail = normalizeEmail(email);
        String searchUrl = config.keycloak().getUrl() + "/admin/realms/" + config.keycloak().getRealm()
                + "/users?email=" + normalizedEmail + "&exact=true";

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
                        LOG.info("Found existing Keycloak user {} for email {}", userId, normalizedEmail);
                        return Uni.createFrom().item(userId);
                    }

                    LOG.info("User not found, creating Keycloak account for {}", normalizedEmail);
                    JsonObject newUser = new JsonObject()
                            .put("username", normalizedEmail)
                            .put("email", normalizedEmail)
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
                                LOG.info("Created Keycloak user {} for email {}", userId, normalizedEmail);
                                return userId;
                            });
                });
    }

    public Uni<KeycloakAuthResult> verifyAuth(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        return Uni.createFrom().item(() -> {
            boolean valid = sessionManager.verifyAndConsumePendingOtp(normalizedEmail, code);
            if (!valid) {
                return new KeycloakAuthResult(false, "Invalid or expired code");
            }
            String token = java.util.UUID.randomUUID().toString();
            sessionManager.storeUserToken(token, normalizedEmail);
            return new KeycloakAuthResult(true, token);
        });
    }

    public Uni<Boolean> startAuth(String email) {
        String normalizedEmail = normalizeEmail(email);
        return getAdminToken()
                .flatMap(adminToken -> findOrCreateUser(normalizedEmail, adminToken))
                .flatMap(userId -> {
                    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
                    sessionManager.storePendingOtp(normalizedEmail, code);
                    LOG.info("OTP generated and stored for {}", normalizedEmail);

                    String htmlBody = "<!DOCTYPE html>"
                            + "<html><head><style>"
                            + "body { margin: 0; padding: 24px; background: #f7f7fb; font-family: Inter, Arial, sans-serif; color: #1f2937; }"
                            + ".card { max-width: 520px; margin: 0 auto; background: #ffffff; border: 1px solid #ececf2; border-radius: 14px; padding: 28px; }"
                            + ".brand { font-size: 22px; font-weight: 700; color: #4f46e5; margin-bottom: 8px; }"
                            + ".title { font-size: 20px; margin: 0 0 12px; }"
                            + ".subtitle { margin: 0 0 18px; color: #4b5563; line-height: 1.45; }"
                            + ".code-box { margin: 16px 0 18px; background: #f3f4ff; border: 1px solid #dfe1ff; border-radius: 12px; text-align: center; padding: 18px; }"
                            + ".code { font-size: 34px; letter-spacing: 6px; font-weight: 700; color: #312e81; font-family: 'Courier New', monospace; }"
                            + ".note { margin: 0; color: #6b7280; font-size: 14px; line-height: 1.45; }"
                            + ".footer { margin-top: 20px; padding-top: 16px; border-top: 1px solid #f0f0f5; color: #9ca3af; font-size: 12px; }"
                            + "</style></head><body>"
                            + "<div class='card'>"
                            + "<div class='brand'>Mixpla</div>"
                            + "<h2 class='title'>Here is your sign-in code</h2>"
                            + "<p class='subtitle'>Use this one-time code to continue. It is valid for 10 minutes.</p>"
                            + "<div class='code-box'>"
                            + "<div class='code'>" + code + "</div>"
                            + "</div>"
                            + "<p class='note'>If you did not request this, you can ignore this message.</p>"
                            + "<div class='footer'>Sent by Mixpla</div>"
                            + "</div></body></html>";

                    Mail mail = Mail.withHtml(
                            normalizedEmail,
                            "Your Mixpla verification code",
                            htmlBody
                    ).setFrom(config.getFromAddress());

                    return mailer.send(mail)
                            .map(v -> {
                                LOG.info("OTP email sent to {}", normalizedEmail);
                                return true;
                            });
                });
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
