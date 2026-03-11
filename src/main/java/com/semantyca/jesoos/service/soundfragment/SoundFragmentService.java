package com.semantyca.jesoos.service.soundfragment;

import com.semantyca.jesoos.dto.SoundFragmentDTO;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentService extends AbstractService<SoundFragment, SoundFragmentDTO> {
    private final SoundFragmentRepository repository;

    protected SoundFragmentService(UserService userService) {
        super(userService);
        this.repository = null;
    }

    @Inject
    public SoundFragmentService(UserService userService, SoundFragmentRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<Integer> getAllCount(final IUser user, final SoundFragmentFilter filter) {
        assert repository != null;
        return repository.getAllCount(user, filter);
    }

    public Uni<List<SoundFragment>> getByTypeAndBrand(PlaylistItemType type, UUID brandId) {
        assert repository != null;
        return repository.findByTypeAndBrand(type, brandId, 100, 0);
    }

}
