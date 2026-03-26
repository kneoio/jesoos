package com.semantyca.jesoos.service;

import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.service.stream.BrandPool;
import com.semantyca.jesoos.service.stream.DjStateService;
import com.semantyca.jesoos.service.stream.AgendaService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandService {

    private static final Logger LOGGER = Logger.getLogger(CommandService.class);

    private final AgendaService agendaService;
    
    @Inject
    DjStateService djStateService;

    @Inject
    BrandPool brandPool;

    @Inject
    public CommandService(AgendaService agendaService) {
        this.agendaService = agendaService;
    }

    public Uni<JsonObject> startBrand(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }

        return brandPool.getRadioStream(brand)
                .map(this::toResponse)
                .invoke(response -> LOGGER.infof("Start brand %s", brand));
    }

    public Uni<JsonObject> enableDj(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        
        return Uni.createFrom().item(() -> {
            djStateService.enableDj(brand);
            LOGGER.infof("DJ enabled for brand: %s (listeners detected)", brand);
            return new JsonObject()
                    .put("success", true)
                    .put("brand", brand)
                    .put("djEnabled", true)
                    .put("message", "DJ intros will be generated");
        });
    }

    public Uni<JsonObject> disableDj(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        
        return Uni.createFrom().item(() -> {
            djStateService.disableDj(brand);
            LOGGER.infof("DJ disabled for brand: %s (no listeners, saving TTS costs)", brand);
            return new JsonObject()
                    .put("success", true)
                    .put("brand", brand)
                    .put("djEnabled", false)
                    .put("message", "DJ intros disabled, songs only mode");
        });
    }

    public Uni<JsonObject> stopBrand(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }

        return brandPool.stopAndRemove(brand)
                .map(stoppedAgenda -> {
                    if (stoppedAgenda != null) {
                        LOGGER.infof("Stopped brand: %s, status: %s", brand, stoppedAgenda.getStatus());
                        return new JsonObject()
                                .put("success", true)
                                .put("brand", brand)
                                .put("status", stoppedAgenda.getStatus().name())
                                .put("message", "Brand stopped and removed from pool");
                    } else {
                        LOGGER.warnf("Brand %s not found in pool", brand);
                        return new JsonObject()
                                .put("success", false)
                                .put("brand", brand)
                                .put("message", "Brand not found in pool");
                    }
                });
    }

    public Uni<JsonObject> stopAllBrands() {
        return Uni.createFrom().item(() -> {
            var onlineStations = brandPool.getOnlineStationsSnapshot();
            int stoppedCount = 0;
            var results = new JsonObject();
            
            for (var station : onlineStations) {
                String brand = station.getSlugName();
                try {
                    brandPool.stopAndRemove(brand).await().indefinitely();
                    stoppedCount++;
                    results.put(brand, "stopped");
                    LOGGER.infof("Stopped brand: %s", brand);
                } catch (Exception e) {
                    results.put(brand, "failed: " + e.getMessage());
                    LOGGER.errorf(e, "Failed to stop brand: %s", brand);
                }
            }
            
            return new JsonObject()
                    .put("success", true)
                    .put("stoppedCount", stoppedCount)
                    .put("totalBrands", onlineStations.size())
                    .put("results", results)
                    .put("message", String.format("Stopped %d out of %d brands", stoppedCount, onlineStations.size()));
        });
    }

    private JsonObject toResponse(ILiveStream agendaHolder) {
        if (agendaHolder == null || agendaHolder.getAgenda() == null) {
            throw new IllegalStateException("Agenda was not created");
        }

        var agenda = agendaHolder.getAgenda();
        String key = agendaHolder.getSlugName() + ":" + agenda.getTotalScenes();
        return new JsonObject()
                .put("success", true)
                .put("key", key)
                .put("totalScenes", agenda.getTotalScenes())
                .put("createdAt", agenda.getCreatedAt());
    }
}
