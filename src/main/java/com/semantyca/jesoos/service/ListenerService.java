package com.semantyca.jesoos.service;

import com.semantyca.core.dto.document.UserDTO;
import com.semantyca.core.model.UserData;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.model.user.UndefinedUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.core.util.WebHelper;
import com.semantyca.jesoos.dto.BrandListenerDTO;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.repository.ListenersRepository;
import com.semantyca.jesoos.util.EmailUtil;
import com.semantyca.mixpla.model.BrandListener;
import com.semantyca.mixpla.model.Listener;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.filter.ListenerFilter;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListenerService extends AbstractService<Listener, ListenerDTO> {
    private static final Logger LOG = Logger.getLogger(ListenerService.class);
    private final ListenersRepository repository;
    private BrandService brandService;
    private record CachedName(String value, long expiresAt) {}
    private static final long DISPLAY_NAME_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    private final ConcurrentHashMap<Long, CachedName> displayNameCache = new ConcurrentHashMap<>();

    protected ListenerService() {
        super();
        this.repository = null;
    }

    @Inject
    public ListenerService(UserService userService,
                           BrandService brandService,
                           ListenersRepository repository) {
        super(userService);
        this.brandService = brandService;
        this.repository = repository;
    }

    public Uni<Listener> getByUserId(long id) {
        assert repository != null;
        return repository.findByUserId(id);
    }

    public Uni<Listener> getById(UUID uuid) {
        assert repository != null;
        return repository.findById(uuid, SuperUser.build(), false);
    }

    public Uni<List<UUID>> getListenersBrands(UUID listener) {
        assert repository != null;
        return repository.getBrandsForListener(listener);
    }

    public Uni<List<Listener>> getListenersForBrand(String brandName, int limit, int offset, IUser user, ListenerFilter filter) {
        assert repository != null;
        return repository.findForBrand(brandName, limit, offset, user, false, filter)
                .map(list -> list.stream().map(BrandListener::getListener).collect(Collectors.toList()));
    }

    public Uni<List<BrandListenerDTO>> getBrandListeners(String brandName, int limit, final int offset, IUser user, ListenerFilter filter) {
        assert repository != null;
        assert brandService != null;

        return repository.findForBrand(brandName, limit, offset, user, false, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<BrandListenerDTO>> unis = list.stream()
                                .map(this::mapToBrandListenerDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }

                });
    }


    public Uni<Void> addBrandToListener(UUID listenerId, UUID brandId) {
        assert repository != null;
        return repository.addBrandToListener(listenerId, brandId);
    }

    public Uni<ListenerDTO> upsert(String id, ListenerDTO dto, String stationSlug, IUser user) {
        assert brandService != null;
        assert repository != null;
        Listener listener = buildEntity(dto);

        if (id == null) {
            if (stationSlug == null) {
                return ensureUserExists(listener, dto.getEmail())
                        .chain(userId -> {
                            listener.setUserId(userId);
                            return repository.insert(listener, dto.getListenerOf(), user);
                        })
                        .chain(this::mapToDTO);
            } else {
                return getBrand(stationSlug)
                        .chain(station -> ensureUserExists(listener, dto.getEmail())
                                .chain(userId -> {
                                    listener.setUserId(userId);
                                    return repository.insert(listener, List.of(station.getId()), user);
                                }))
                        .chain(this::mapToDTO);
            }
        } else {
            UUID listenerUUID = UUID.fromString(id);
            if (stationSlug == null) {
                return repository.update(listenerUUID, listener, dto.getListenerOf(), user)
                        .chain(updatedListener -> {
                            if (dto.getEmail() != null && !dto.getEmail().isEmpty()) {
                                return userService.updateEmail(dto.getUserId(), dto.getEmail(), user)
                                        .replaceWith(updatedListener);
                            }
                            return Uni.createFrom().item(updatedListener);
                        })
                        .chain(this::mapToDTO);
            } else {
                return getBrand(stationSlug)
                        .chain(station -> repository.getBrandsForListener(listenerUUID)
                                .chain(stationIds -> {
                                    return repository.update(listenerUUID, listener, stationIds, user);
                                }))
                        .chain(this::mapToDTO);
            }
        }
    }

    public Uni<String> resolveDisplayName(long userId, String fallback) {
        CachedName cached = displayNameCache.get(userId);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt()) {
            return Uni.createFrom().item(cached.value());
        }
        return getByUserId(userId)
                .map(listener -> {
                    if (listener == null) {
                        LOG.warnf("[resolveDisplayName] no listener found for userId=%d, returning fallback", userId);
                        return fallback;
                    }
                    if (listener.getUserData() != null && listener.getUserData().getData() != null) {
                        String name = listener.getUserData().getData().get("preferred_name");
                        if (name != null && !name.isBlank()) {
                            LOG.debugf("[resolveDisplayName] userId=%d → preferred_name=%s", userId, name);
                            return name;
                        }
                    }
                    if (listener.getLocalizedName() != null) {
                        String name = listener.getLocalizedName().get(LanguageCode.en);
                        if (name != null && !name.isBlank()) {
                            LOG.debugf("[resolveDisplayName] userId=%d → localizedName=%s", userId, name);
                            return name;
                        }
                    }
                    if (listener.getNickName() != null) {
                        Set<String> nicks = listener.getNickName().get(LanguageCode.en);
                        if (nicks != null && !nicks.isEmpty()) {
                            String nick = nicks.iterator().next();
                            LOG.debugf("[resolveDisplayName] userId=%d → nickName=%s", userId, nick);
                            return nick;
                        }
                    }
                    LOG.warnf("[resolveDisplayName] userId=%d — listener found but all name fields empty, returning fallback", userId);
                    return fallback;
                })
                .invoke(name -> {
                    if (name != null && !name.equals(fallback)) displayNameCache.put(userId,
                            new CachedName(name, System.currentTimeMillis() + DISPLAY_NAME_TTL_MS));
                })
                .onFailure().recoverWithItem(fallback);
    }

    public void invalidateDisplayNameCache(long userId) {
        displayNameCache.remove(userId);
    }

    public Uni<List<Listener>> findCommunityMembers(String brandSlug, UUID excludeListenerId, String fieldName, String fieldValue) {
        assert repository != null;
        return repository.findByUserDataFieldInBrand(brandSlug, excludeListenerId, fieldName, fieldValue);
    }

    public Uni<List<Listener>> findCommunityMembersByInterest(String brandSlug, UUID excludeListenerId, String interest, String city) {
        assert repository != null;
        return repository.findByInterestAndCityInBrand(brandSlug, excludeListenerId, interest, city);
    }

    public Uni<Void> updateUserData(UUID listenerId, UserData userData) {
        assert repository != null;
        return repository.updateUserData(listenerId, userData);
    }

    public Uni<Void> updateLabels(UUID listenerId, List<UUID> labels) {
        assert repository != null;
        return repository.updateLabels(listenerId, labels);
    }

    public Uni<Listener> update(UUID id, Listener listener, String stationSlug) {
        assert repository != null;
        return repository.getBrandsForListener(id)
                .chain(brandIds -> repository.update(id, listener, brandIds, SuperUser.build()));
    }

    private Uni<Brand> getBrand(String stationSlug) {
        return brandService.getBySlugName(stationSlug)
                .chain(station -> {
                    if (station == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Station not found: " + stationSlug));
                    }
                    return Uni.createFrom().item(station);
                });
    }


    private Listener buildEntity(ListenerDTO dto) {
        Listener doc = new Listener();
        doc.setLocalizedName(dto.getLocalizedName());
        doc.setNickName(dto.getNickName());
        if (dto.getUserData() != null && !dto.getUserData().isEmpty()) {
            doc.setUserData(new UserData(dto.getUserData()));
        }
        if (dto.getListenerOf() != null) {
            doc.setListenerOf(dto.getListenerOf());
        }
        if (dto.getLabels() != null) {
            doc.setLabels(dto.getLabels());
        }
        return doc;
    }

    private Uni<Long> ensureUserExists(Listener listener, String email) {
        String normalizedEmail = EmailUtil.normalize(email);
        return userService.findByEmail(normalizedEmail)
                .chain(existingUser -> {
                    if (existingUser.getId() != UndefinedUser.ID) {
                        return Uni.createFrom().item(existingUser.getId());
                    }
                    return createNewUser(listener, normalizedEmail);
                });
    }


    private Uni<Long> createNewUser(Listener listener, String email) {
        String normalizedEmail = EmailUtil.normalize(email);
        UserDTO userDTO = new UserDTO();
        String slugName = WebHelper.generateSlug(normalizedEmail);
        userDTO.setLogin(slugName);
        userDTO.setEmail(normalizedEmail);
        return userService.add(userDTO, true);
    }

    private Uni<ListenerDTO> mapToDTO(Listener doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                repository.getBrandsForListener(doc.getId()),
                userService.get(doc.getUserId())
        ).asTuple().map(tuple -> {
            ListenerDTO dto = new ListenerDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setUserId(doc.getUserId());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setNickName(doc.getNickName());
            if (doc.getUserData() != null) {
                dto.setUserData(doc.getUserData().getData());
            }
            List<UUID> brandIds = tuple.getItem3();
            dto.setListenerOf(brandIds);
            dto.setLabels(doc.getLabels());
            Optional<IUser> userOptional = tuple.getItem4();
            userOptional.ifPresent(user -> {
                dto.setEmail(user.getEmail());
                dto.setSlugName(user.getLogin());
            });
            return dto;
        });
    }

    private Uni<BrandListenerDTO> mapToBrandListenerDTO(BrandListener brandListener) {
        return mapToDTO(brandListener.getListener())
                .onItem().transform(listenerDTO -> {
                    BrandListenerDTO dto = new BrandListenerDTO();
                    dto.setId(brandListener.getId());
                    dto.setListenerDTO(listenerDTO);
                    return dto;
                });
    }

}