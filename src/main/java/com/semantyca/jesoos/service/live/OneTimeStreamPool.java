package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.model.stream.OneTimeStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OneTimeStreamPool extends AbstractStreamPool<OneTimeStream> {
    private static final Logger LOGGER = Logger.getLogger(OneTimeStreamPool.class);

    private final ScenePool scenePool;
    private final DjStateService djStateService;

    @Inject
    public OneTimeStreamPool(ScenePool scenePool, DjStateService djStateService) {
        this.scenePool = scenePool;
        this.djStateService = djStateService;
    }

    public void add(OneTimeStream stream) {
        pool.put(stream.getSlugName(), stream);
        LOGGER.infof("OneTimeStreamPool: stream '%s' added (streamId=%s)", stream.getSlugName(), stream.getStreamId());
    }

    @Override
    protected void onRemoved(String slugName, OneTimeStream stream) {
        scenePool.removeActiveScene(slugName);
        djStateService.remove(slugName);
    }

    @Override
    protected Logger getLogger() {
        return LOGGER;
    }
}
