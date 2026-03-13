package com.semantyca.jesoos.repository.soundfragment;

import com.semantyca.core.repository.IFileStorage;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.semantyca.mixpla.repository.MixplaNameResolver.SOUND_FRAGMENT;


@ApplicationScoped
public class SoundFragmentFileRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentFileRepository.class);
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SOUND_FRAGMENT);

    private final PgPool client;
    private final IFileStorage fileStorage;
    private final SoundFragmentFileHandler fileHandler;
    private final RLSRepository rlsRepository;

    @Inject
    public SoundFragmentFileRepository(PgPool client, @Named("hetzner") IFileStorage fileStorage,
                                       SoundFragmentFileHandler fileHandler, RLSRepository rlsRepository) {
        this.client = client;
        this.fileStorage = fileStorage;
        this.fileHandler = fileHandler;
        this.rlsRepository = rlsRepository;
    }


}