package com.semantyca.jesoos.service.ask;

import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.external.KeycloakAuthService;
import com.semantyca.jesoos.service.chat.PublicChatSessionManager;
import com.semantyca.jesoos.util.EmailUtil;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Ask-only auth: OTP session tokens first, then Keycloak OIDC access tokens.
 * Same email resolves to the same {@link IUser}, so chat history continues across both paths.
 */
@ApplicationScoped
public class AskAuthService {

    private static final Logger LOG = Logger.getLogger(AskAuthService.class);

    public record Result(IUser user, String sessionToken) {
        public static Result anonymous() {
            return new Result(AnonymousUser.build(), null);
        }
    }

    @Inject PublicChatSessionManager sessionManager;
    @Inject UserService userService;
    @Inject KeycloakAuthService keycloakAuthService;

    public Uni<Result> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Uni.createFrom().item(Result.anonymous());
        }
        return sessionManager.validateSessionAndGetEmail(token)
                .chain(email -> {
                    if (email != null) {
                        return resolveLocalUser(email)
                                .map(user -> isAnonymous(user)
                                        ? Result.anonymous()
                                        : new Result(user, token));
                    }
                    if (!looksLikeJwt(token)) {
                        LOG.warnf("[ask-auth] unknown token shape — treating as anonymous");
                        return Uni.createFrom().item(Result.anonymous());
                    }
                    return authenticateViaOidc(token);
                });
    }

    private Uni<Result> authenticateViaOidc(String accessToken) {
        return keycloakAuthService.resolveEmailFromAccessToken(accessToken)
                .chain(email -> {
                    if (email == null || email.isBlank()) {
                        LOG.warnf("[ask-auth] OIDC userinfo had no email — anonymous");
                        return Uni.createFrom().item(Result.anonymous());
                    }
                    return resolveLocalUser(email)
                            .chain(user -> {
                                if (isAnonymous(user)) {
                                    LOG.warnf("[ask-auth] OIDC email %s has no local user — anonymous", email);
                                    return Uni.createFrom().item(Result.anonymous());
                                }
                                String askToken = UUID.randomUUID().toString();
                                return sessionManager.storeUserToken(askToken, email)
                                        .replaceWith(new Result(user, askToken))
                                        .invoke(() -> LOG.infof(
                                                "[ask-auth] OIDC OK — userId=%d email=%s minted ask session",
                                                user.getId(), email));
                            });
                });
    }

    private Uni<IUser> resolveLocalUser(String email) {
        return userService.findByEmail(EmailUtil.normalize(email))
                .map(user -> (user == null || user.getId() == 0) ? AnonymousUser.build() : user);
    }

    private static boolean isAnonymous(IUser user) {
        return user instanceof AnonymousUser || user.getId() == 0;
    }

    /** OTP sessions are UUIDs; OIDC access tokens are JWTs (header.payload.sig). */
    static boolean looksLikeJwt(String token) {
        int first = token.indexOf('.');
        if (first <= 0) return false;
        int second = token.indexOf('.', first + 1);
        return second > first + 1 && second < token.length() - 1 && token.indexOf('.', second + 1) < 0;
    }
}
