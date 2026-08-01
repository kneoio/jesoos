package com.semantyca.jesoos.service.ask;

import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.external.KeycloakAuthService;
import com.semantyca.jesoos.util.EmailUtil;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Ask-only auth: Keycloak OIDC access tokens exclusively.
 * Ask lives inside the protected area — there is no in-chat sign-in and no anonymous access.
 */
@ApplicationScoped
public class AskAuthService {

    private static final Logger LOG = Logger.getLogger(AskAuthService.class);

    public record Result(IUser user) {}

    @Inject UserService userService;
    @Inject KeycloakAuthService keycloakAuthService;

    /** Resolves the OIDC access token to a local user; the caller rejects anonymous results. */
    public Uni<Result> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Uni.createFrom().item(new Result(AnonymousUser.build()));
        }
        return keycloakAuthService.resolveEmailFromAccessToken(token)
                .chain(email -> {
                    if (email == null || email.isBlank()) {
                        LOG.warnf("[ask-auth] OIDC userinfo had no email");
                        return Uni.createFrom().item(new Result(AnonymousUser.build()));
                    }
                    return resolveLocalUser(email)
                            .invoke(user -> {
                                if (isAnonymous(user)) {
                                    LOG.warnf("[ask-auth] OIDC email %s has no local user", email);
                                } else {
                                    LOG.infof("[ask-auth] OIDC OK — userId=%d email=%s", user.getId(), email);
                                }
                            })
                            .map(Result::new);
                });
    }

    private Uni<IUser> resolveLocalUser(String email) {
        return userService.findByEmail(EmailUtil.normalize(email))
                .map(user -> (user == null || user.getId() == 0) ? AnonymousUser.build() : user);
    }

    public static boolean isAnonymous(IUser user) {
        return user instanceof AnonymousUser || user.getId() == 0;
    }
}
