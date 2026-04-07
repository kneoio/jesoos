package com.semantyca.jesoos.external;

import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

/**
 * Keycloak integration for email-based OTP authentication.
 *
 * Required Keycloak setup (realm: configured via keycloak.realm):
 *   - Client "jesoos-app": Direct Access Grants enabled, Service Accounts enabled
 *   - Service account roles: realm-management -> view-users, manage-users
 *   - Direct Grant authentication flow: Username -> Email OTP Form
 *   - SMTP configured in realm settings
 *
 * Flow:
 *   startAuth(email) -> Admin API creates user if needed -> execute-actions-email sends OTP
 *   verifyAuth(email, code) -> token endpoint with email + OTP code -> access_token returned
 */
@ApplicationScoped
public class KeycloakAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAuthService.class);

    @Inject
    Vertx vertx;

    @Inject
    ReactiveMailer mailer;

    @Inject
    PublicChatSessionManager sessionManager;

    @ConfigProperty(name = "jesoos.from-address", defaultValue = "noreply@jesoos.app")
    String fromAddress;

    private static final SecureRandom RANDOM = new SecureRandom();

    @ConfigProperty(name = "keycloak.url")
    String keycloakUrl;

    @ConfigProperty(name = "keycloak.realm")
    String realm;

    @ConfigProperty(name = "keycloak.client-id")
    String clientId;

    @ConfigProperty(name = "keycloak.client-secret")
    String clientSecret;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    // -------------------------------------------------------------------------
    // Admin token via service account client credentials
    // -------------------------------------------------------------------------

    private Uni<String> getAdminToken() {
        String url = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        MultiMap form = MultiMap.caseInsensitiveMultiMap()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret);

        return webClient.postAbs(url)
                .sendForm(form)
                .map(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Failed to get admin token: HTTP " + resp.statusCode());
                    }
                    return resp.bodyAsJsonObject().getString("access_token");
                });
    }

    // -------------------------------------------------------------------------
    // Find or create user in Keycloak, return Keycloak user ID
    // -------------------------------------------------------------------------

    private Uni<String> findOrCreateUser(String email, String adminToken) {
        String searchUrl = keycloakUrl + "/admin/realms/" + realm
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
                    if (users != null && !users.isEmpty()) {
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

                    String createUrl = keycloakUrl + "/admin/realms/" + realm + "/users";
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
                                // Keycloak returns the new user URL in the Location header
                                String location = createResp.getHeader("Location");
                                String userId = location.substring(location.lastIndexOf('/') + 1);
                                LOG.info("Created Keycloak user {} for email {}", userId, email);
                                return userId;
                            });
                });
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public record KeycloakAuthResult(boolean success, String message) {}

    /**
     * Verifies the OTP code for the given email.
     *
     * @return KeycloakAuthResult with success=true and the access token on success,
     *         or success=false with an error message on failure
     */
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

    /**
     * Initiates passwordless authentication:
     *   1. Ensures user exists in Keycloak (creates if needed)
     *   2. Generates a 6-digit OTP, stores it locally, and sends it via email
     *
     * @return true if the email was dispatched successfully
     */
    public Uni<Boolean> startAuth(String email) {
        return getAdminToken()
                .flatMap(adminToken -> findOrCreateUser(email, adminToken))
                .flatMap(userId -> {
                    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
                    sessionManager.storePendingOtp(email, code);
                    LOG.info("OTP generated and stored for {}", email);

                    Mail mail = Mail.withText(
                            email,
                            "Your Jesoos verification code",
                            "Your verification code is: " + code + "\n\nValid for 10 minutes.\n\nDo not share this code."
                    ).setFrom(fromAddress);

                    return mailer.send(mail)
                            .map(v -> {
                                LOG.info("OTP email sent to {}", email);
                                return true;
                            });
                });
    }
}
