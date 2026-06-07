package com.semantyca.jesoos.service.chat;

import com.semantyca.core.model.UserData;
import com.semantyca.core.model.user.AnonymousUser;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.repository.ChatRepository;
import com.semantyca.jesoos.service.BrandService;
import com.semantyca.jesoos.service.ListenerService;
import com.semantyca.jesoos.util.EmailUtil;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ChatAuthService {

    private static final Logger LOG = Logger.getLogger(ChatAuthService.class);

    @Inject PublicChatSessionManager sessionManager;
    @Inject UserService userService;
    @Inject ListenerService listenerService;
    @Inject BrandService brandService;
    @Inject ChatRepository chatRepository;

    public Uni<IUser> authenticateUserFromToken(String token) {
        if (token == null || token.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Token is required"));
        }
        return sessionManager.validateSessionAndGetEmail(token)
                .onItem().transformToUni(email -> {
                    if (email == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Invalid or expired token"));
                    }
                    return userService.findByEmail(EmailUtil.normalize(email))
                            .onItem().transformToUni(user -> {
                                if (user == null || user.getId() == 0) {
                                    return Uni.createFrom().item(AnonymousUser.build());
                                }
                                return Uni.createFrom().item(user);
                            });
                });
    }

    public Uni<RegistrationResult> registerListener(IUser knownUser, String email, String stationSlug, String preferredName) {
        String normalizedEmail = EmailUtil.normalize(email);
        String userToken = UUID.randomUUID().toString();
        return sessionManager.storeUserToken(userToken, normalizedEmail)
                .chain(() -> registerListenerForUser(knownUser, normalizedEmail, stationSlug, preferredName, userToken));
    }

    private Uni<RegistrationResult> registerListenerForUser(IUser user, String normalizedEmail, String stationSlug, String preferredName, String userToken) {
        if (user == null || user.getId() == 0) {
            ListenerDTO dto = new ListenerDTO();
            dto.setEmail(normalizedEmail);
            if (preferredName != null && !preferredName.isBlank()) {
                dto.setUserData(Map.of("preferred_name", preferredName));
            }
            return listenerService.upsert(null, dto, stationSlug, SuperUser.build())
                    .map(listenerDTO -> new RegistrationResult(listenerDTO.getUserId(), userToken));
        }

        return listenerService.getByUserId(user.getId())
                .chain(listener -> {
                    if (listener != null) {
                        Uni<Void> storeNameUni = (preferredName != null && !preferredName.isBlank())
                                ? listenerService.updateUserData(listener.getId(),
                                        mergeUserData(listener.getUserData(), preferredName))
                                : Uni.createFrom().voidItem();
                        return storeNameUni
                                .chain(() -> ensureUserIsListenerOfStation(user.getId(), stationSlug))
                                .replaceWith(new RegistrationResult(user.getId(), userToken));
                    }
                    ListenerDTO dto = new ListenerDTO();
                    dto.setEmail(normalizedEmail);
                    if (preferredName != null && !preferredName.isBlank()) {
                        dto.setUserData(Map.of("preferred_name", preferredName));
                    }
                    return listenerService.upsert(null, dto, stationSlug, SuperUser.build())
                            .map(listenerDTO -> new RegistrationResult(user.getId(), userToken));
                });
    }

    public Uni<Void> ensureUserIsListenerOfStation(long userId, String stationSlug) {
        return listenerService.getByUserId(userId)
                .chain(listener -> {
                    if (listener == null) {
                        return Uni.createFrom().voidItem();
                    }
                    return brandService.getBySlugName(stationSlug)
                            .chain(station -> {
                                if (station == null) {
                                    return Uni.createFrom().voidItem();
                                }
                                return listenerService.getListenersBrands(listener.getId())
                                        .chain(currentStations -> {
                                            if (!currentStations.contains(station.getId())) {
                                                return listenerService.addBrandToListener(listener.getId(), station.getId());
                                            }
                                            return Uni.createFrom().voidItem();
                                        });
                            });
                });
    }

    public Uni<Void> migrateAnonymousDbRecords(String connectionId, long newUserId) {
        return chatRepository.migrateAnonymousDbRecords(connectionId, newUserId);
    }

    private UserData mergeUserData(UserData existing, String value) {
        UserData merged = new UserData(existing != null && existing.getData() != null
                ? new HashMap<>(existing.getData())
                : new HashMap<>());
        merged.getData().put("preferred_name", value);
        return merged;
    }
}
