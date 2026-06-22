package com.semantyca.jesoos.service;

import com.semantyca.jesoos.outbound.InternalRestCall;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PlaylistQueueService {

    @Inject
    InternalRestCall internalRestCall;

    public Uni<Void> resetQueue(String brandSlug) {
        return Uni.createFrom().voidItem();
    }

    public Uni<JsonArray> getQueueByBrandSlug(String brandSlug) {
        return internalRestCall.getQueueFromAivox(brandSlug)
                .onFailure().recoverWithItem(new JsonArray());
    }
}
