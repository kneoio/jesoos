package com.semantyca.djinn.service;

import com.semantyca.djinn.dto.BrandListenerDTO;
import com.semantyca.djinn.dto.ListenerDTO;
import com.semantyca.djinn.dto.ListenerFilterDTO;
import com.semantyca.djinn.repository.ListenersRepository;
import com.semantyca.mixpla.model.BrandListener;
import com.semantyca.mixpla.model.Listener;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.filter.ListenerFilter;
import io.kneo.core.dto.document.UserDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.UndefinedUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListenerService extends AbstractService<Listener, ListenerDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerService.class);
    private final ListenersRepository repository;
    private final Validator validator;
    private BrandService brandService;

    protected ListenerService() {
        super();
        this.repository = null;
        this.validator = null;
    }

    @Inject
    public ListenerService(UserService userService,
                           BrandService brandService,
                           Validator validator,
                           ListenersRepository repository) {
        super(userService);
        this.brandService = brandService;
        this.validator = validator;
        this.repository = repository;
    }

    public Uni<List<ListenerDTO>> getAllDTO(final int limit, final int offset, final IUser user, final ListenerFilterDTO filterDTO) {
        assert repository != null;
        ListenerFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<ListenerDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user, final ListenerFilterDTO filterDTO) {
        assert repository != null;
        ListenerFilter filter = toFilter(filterDTO);
        return repository.getAllCount(user, false, filter);
    }



    @Override
    public Uni<ListenerDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;
        return repository.findById(uuid, user, false)
                .chain(this::mapToDTO);
    }

    public Uni<Listener> getByUserId(long id) {
        assert repository != null;
        return repository.findByUserId(id);
    }

    public Uni<List<UUID>> getListenersBrands(UUID listener) {
        assert repository != null;
        return repository.getBrandsForListener(listener);
    }


    public Uni<List<BrandListenerDTO>> getBrandListeners(String brandName, int limit, final int offset, IUser user, ListenerFilterDTO filterDTO) {
        assert repository != null;
        assert brandService != null;

        ListenerFilter filter = toFilter(filterDTO);
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

    public Uni<Integer> getCountBrandListeners(final String brand, final IUser user, final ListenerFilterDTO filterDTO) {
        assert repository != null;
        ListenerFilter filter = toFilter(filterDTO);
        return repository.findForBrandCount(brand, user, false, filter);
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

    private Uni<Long> ensureUserExists(Listener listener, String email) {
        return userService.findByEmail(email)
                .chain(existingUser -> {
                    if (existingUser.getId() != UndefinedUser.ID) {
                        return Uni.createFrom().item(existingUser.getId());
                    }
                    return createNewUser(listener, email);
                });
    }

    private Uni<Long> createNewUser(Listener listener, String email) {
        UserDTO userDTO = new UserDTO();
        String slugName = WebHelper.generatePersonSlug(listener.getLocalizedName().get(LanguageCode.en));
        userDTO.setLogin(slugName);
        userDTO.setEmail(email);
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
            dto.setArchived(doc.getArchived());
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


    private ListenerFilter toFilter(ListenerFilterDTO dto) {
        if (dto == null) {
            return null;
        }

        ListenerFilter filter = new ListenerFilter();
        filter.setActivated(dto.isActivated());
        filter.setCountries(dto.getCountries());
        filter.setSearchTerm(dto.getSearchTerm());

        return filter;
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return null;
    }

}