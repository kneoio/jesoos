package com.semantyca.jesoos.service;

import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.BrandListenerDTO;
import com.semantyca.jesoos.dto.ListenerDTO;
import com.semantyca.jesoos.repository.ListenersRepository;
import com.semantyca.mixpla.model.BrandListener;
import com.semantyca.mixpla.model.Listener;
import com.semantyca.mixpla.model.filter.ListenerFilter;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListenerService extends AbstractService<Listener, ListenerDTO> {
    private final ListenersRepository repository;
    private BrandService brandService;

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


    public Uni<Integer> getAllCount(final IUser user, final ListenerFilter filter) {
        assert repository != null;
        return repository.getAllCount(user, false, filter);
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

}