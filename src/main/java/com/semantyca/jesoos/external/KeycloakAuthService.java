package com.semantyca.jesoos.external;

import com.semantyca.core.service.mail.MailService;
import com.semantyca.jesoos.config.JesoosConfig;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import com.semantyca.jesoos.util.EmailUtil;
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
    MailService mailService;

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
        String normalizedEmail = EmailUtil.normalize(email);
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

    /**
     * Resolve email from a Keycloak OIDC access token via the userinfo endpoint.
     * Returns null when the token is invalid or has no email claim.
     */
    public Uni<String> resolveEmailFromAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        String url = config.keycloak().getUrl() + "/realms/" + config.keycloak().getRealm()
                + "/protocol/openid-connect/userinfo";
        return webClient.getAbs(url)
                .putHeader("Authorization", "Bearer " + accessToken)
                .send()
                .map(resp -> {
                    if (resp.statusCode() != 200) {
                        LOG.debug("Keycloak userinfo failed: HTTP {}", resp.statusCode());
                        return null;
                    }
                    JsonObject body = resp.bodyAsJsonObject();
                    String email = body.getString("email");
                    if (email == null || email.isBlank()) {
                        email = body.getString("preferred_username");
                    }
                    return EmailUtil.normalize(email);
                })
                .onFailure().invoke(err -> LOG.debug("Keycloak userinfo error: {}", err.getMessage()))
                .onFailure().recoverWithItem(err -> null);
    }

    public Uni<KeycloakAuthResult> verifyAuth(String email, String code) {
        String normalizedEmail = EmailUtil.normalize(email);
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
        String normalizedEmail = EmailUtil.normalize(email);
        return getAdminToken()
                .flatMap(adminToken -> findOrCreateUser(normalizedEmail, adminToken))
                .flatMap(userId -> {
                    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
                    sessionManager.storePendingOtp(normalizedEmail, code);
                    LOG.info("OTP generated and stored for {}", normalizedEmail);

                    return mailService.sendOtp(
                                    normalizedEmail,
                                    code,
                                    "Your Mixpla verification code",
                                    "Here is your sign-in code",
                                    "Use this one-time code to continue. It is valid for 10 minutes.",
                                    "If you did not request this, you can ignore this message.",
                                    "Sent by Mixpla")
                            .map(v -> {
                                LOG.info("OTP email sent to {}", normalizedEmail);
                                return true;
                            });
                });
    }

}
