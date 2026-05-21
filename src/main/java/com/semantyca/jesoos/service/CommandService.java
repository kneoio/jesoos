package com.semantyca.jesoos.service;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.agenda.AgendaService;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.DjStateService;
import com.semantyca.jesoos.service.live.OtsStreamScheduler;
import com.semantyca.jesoos.service.live.ScenePool;
import com.semantyca.jesoos.service.live.StaggeredSongScheduler;
import com.semantyca.mixpla.dto.queue.command.CommandDTO;
import com.semantyca.mixpla.dto.queue.command.CommandType;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CommandService {
    private static final Logger LOGGER = Logger.getLogger(CommandService.class);
    private final DjStateService djStateService;
    private final BrandPool brandPool;
    private final StaggeredSongScheduler staggeredSongScheduler;
    private final OneTimeStreamService oneTimeStreamService;
    private final OtsStreamScheduler otsStreamScheduler;
    private final MetricPublisher metricPublisher;
    private final BrandService brandService;
    private final AgendaService agendaService;

    @Inject
    public CommandService(DjStateService djStateService, BrandPool brandPool, ScenePool scenePool,
                          StaggeredSongScheduler staggeredSongScheduler,
                          OneTimeStreamService oneTimeStreamService,
                          OtsStreamScheduler otsStreamScheduler,
                          MetricPublisher metricPublisher,
                          BrandService brandService,
                          AgendaService agendaService) {
        this.djStateService = djStateService;
        this.brandPool = brandPool;
        this.staggeredSongScheduler = staggeredSongScheduler;
        this.oneTimeStreamService = oneTimeStreamService;
        this.otsStreamScheduler = otsStreamScheduler;
        this.metricPublisher = metricPublisher;
        this.brandService = brandService;
        this.agendaService = agendaService;
    }

    public Uni<Void> handleQueueCommand(CommandDTO dto) {
        if (dto.type() == CommandType.FLOW_RESTART && "brand_saved".equals(dto.command())) {
            return handleBrandSaved(dto);
        }
        LOGGER.debugf("Ignored queue command: type=%s command=%s", dto.type(), dto.command());
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> handleBrandSaved(CommandDTO dto) {
        Object rawSlug = dto.payload() != null ? dto.payload().get("slug") : null;
        if (rawSlug == null) {
            LOGGER.warn("brand_saved command missing slug in payload");
            return Uni.createFrom().voidItem();
        }
        String slug = rawSlug.toString();
        return brandPool.get(slug)
                .chain(stream -> {
                    if (stream == null) {
                        LOGGER.infof("Brand %s not active, skipping agenda rebuild", slug);
                        return Uni.createFrom().voidItem();
                    }
                    return brandService.getBySlugName(slug)
                            .chain(brand -> agendaService.getStreamAgenda(brand, SuperUser.build())
                                    .invoke(newAgenda -> {
                                        stream.setAgenda(newAgenda);
                                        LOGGER.infof("Rebuilt agenda for brand %s with %d scenes",
                                                slug, newAgenda.getTotalScenes());
                                    })
                                    .replaceWithVoid());
                });
    }

    public Uni<JsonObject> startBrand(String brand) {
        return brandPool.getRadioStream(brand)
                .map(this::toResponse)
                .invoke(response -> LOGGER.infof("Start brand %s", brand));
    }

    public Uni<JsonObject> enableDj(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        
        return brandPool.get(brand)
                .chain(stream -> {
                    if (stream == null) {
                        LOGGER.infof("Brand %s not in pool, starting stream before enabling DJ", brand);
                        return brandPool.getRadioStream(brand)
                                .invoke(s -> {
                                    djStateService.enableDj(brand);
                                })
                                .map(this::toResponse)
                                .map(response -> response
                                        .put("djEnabled", true)
                                        .put("message", "Stream started and DJ intros will be generated"));
                    } else {
                        djStateService.enableDj(brand);
                        LOGGER.infof("DJ enabled for brand: %s (stream already running)", brand);
                        return Uni.createFrom().item(new JsonObject()
                                .put("success", true)
                                .put("brand", brand)
                                .put("djEnabled", true)
                                .put("message", "DJ intros will be generated"));
                    }
                });
    }

    public Uni<JsonObject> disableDj(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        
        return Uni.createFrom().item(() -> {
            djStateService.disableDj(brand);
            return new JsonObject()
                    .put("success", true)
                    .put("brand", brand)
                    .put("djEnabled", false)
                    .put("message", "DJ intros disabled, songs only mode");
        });
    }

    public Uni<JsonObject> backpressure(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        return Uni.createFrom().item(() -> {
            int pending = staggeredSongScheduler.backpressure(brand);
            return new JsonObject()
                    .put("success", true)
                    .put("brand", brand)
                    .put("pendingSkips", pending);
        });
    }

    public Uni<Boolean> getDjStatus(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        
        return Uni.createFrom().item(() -> djStateService.isDjEnabled(brand));
    }

    public Uni<JsonObject> stopBrand(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }

        djStateService.disableDj(brand);
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

    public Uni<JsonObject> emitTimelineEntry(String brand, UUID sceneId, int sequenceNumber) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        if (sceneId == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing sceneId parameter"));
        }

        return brandPool.get(brand)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not in pool: " + brand));
                    }

                    StreamAgenda agenda = stream.getAgenda();
                    if (agenda == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("No agenda found for brand: " + brand));
                    }

                    LiveScene scene = agenda.getLiveScenes().stream()
                            .filter(s -> s.getSceneId().equals(sceneId))
                            .findFirst()
                            .orElse(null);

                    if (scene == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(
                                "Scene with ID " + sceneId + " not found in agenda for brand: " + brand));
                    }

                    TimelineEntry entry = scene.getTimeline().stream()
                            .filter(e -> e.getSequenceNumber() == sequenceNumber)
                            .findFirst()
                            .orElse(null);

                    if (entry == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(
                                "Timeline entry with sequence number " + sequenceNumber + " not found in scene " + sceneId));
                    }

                    LOGGER.infof("Manually emitting timeline entry #%d from scene %s ('%s') for brand: %s", 
                            sequenceNumber, sceneId, scene.getSceneTitle(), brand);
                    
                    metricPublisher.publishMetric(
                            brand,
                            MetricEventType.COMMAND,
                            ProcessType.INDEPENDENT,
                            "command_emit_timeline_entry",
                            Map.of(
                                    "sceneId", sceneId.toString(),
                                    "sceneTitle", scene.getSceneTitle(),
                                    "sequenceNumber", sequenceNumber
                            ),
                            scene.getTraceId()
                    );
                    
                    return staggeredSongScheduler.emitTimelineEntry(brand, scene, entry, scene.getTimeZone(), 8)
                            .map(v -> new JsonObject()
                                    .put("success", true)
                                    .put("brand", brand)
                                    .put("sceneId", sceneId.toString())
                                    .put("sceneTitle", scene.getSceneTitle())
                                    .put("sequenceNumber", sequenceNumber)
                                    .put("message", "Timeline entry emitted"));
                })
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.errorf(failure, "Failed to emit timeline entry #%d from scene %s for brand: %s", sequenceNumber, sceneId, brand);
                    return new JsonObject()
                            .put("success", false)
                            .put("brand", brand)
                            .put("sceneId", sceneId.toString())
                            .put("sequenceNumber", sequenceNumber)
                            .put("error", failure.getMessage());
                });
    }

    public Uni<JsonObject> startOts(String otsSlugName) {
        return oneTimeStreamService.start(otsSlugName)
                .map(stream -> new JsonObject()
                        .put("success", true)
                        .put("otsSlugName", stream.getSlugName())
                        .put("status", stream.getStatus().name())
                        .put("message", "OTS stream started"));
    }

    public Uni<JsonObject> stopOts(String otsSlugName) {
        otsStreamScheduler.cancelOtsTimers(otsSlugName);
        return Uni.createFrom().item(new JsonObject()
                .put("success", true)
                .put("otsSlugName", otsSlugName)
                .put("message", "OTS stream stopped"));
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
