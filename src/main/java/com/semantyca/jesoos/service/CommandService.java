package com.semantyca.jesoos.service;

import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.cnst.BoostType;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.DjStateService;
import com.semantyca.jesoos.service.live.OtsStreamScheduler;
import com.semantyca.jesoos.service.live.ScenePool;
import com.semantyca.jesoos.service.live.StaggeredSongScheduler;
import com.semantyca.jesoos.service.chat.ChatAuthService;
import com.semantyca.jesoos.repository.SoundFragmentRatingLogRepository;
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
    private final ChatAuthService chatAuthService;
    private final SoundFragmentRatingLogRepository ratingLogRepository;

    @Inject
    public CommandService(DjStateService djStateService, BrandPool brandPool, ScenePool scenePool,
                          StaggeredSongScheduler staggeredSongScheduler,
                          OneTimeStreamService oneTimeStreamService,
                          OtsStreamScheduler otsStreamScheduler,
                          MetricPublisher metricPublisher,
                          BrandService brandService,
                          ChatAuthService chatAuthService,
                          SoundFragmentRatingLogRepository ratingLogRepository) {
        this.djStateService = djStateService;
        this.brandPool = brandPool;
        this.staggeredSongScheduler = staggeredSongScheduler;
        this.oneTimeStreamService = oneTimeStreamService;
        this.otsStreamScheduler = otsStreamScheduler;
        this.metricPublisher = metricPublisher;
        this.brandService = brandService;
        this.chatAuthService = chatAuthService;
        this.ratingLogRepository = ratingLogRepository;
    }

    public Uni<Void> handleQueueCommand(CommandDTO dto) {
        if (dto.type() == CommandType.FLOW_RESTART && "brand_saved".equals(dto.command())) {
            return handleBrandSaved(dto);
        }
        if (dto.type() == CommandType.SONG_RATED && "song_rated".equals(dto.command())) {
            return handleSongRated(dto);
        }
        LOGGER.warnf("Ignored queue command: type=%s command=%s", dto.type(), dto.command());
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> handleSongRated(CommandDTO dto) {
        Map<String, Object> p = dto.payload();
        if (p == null) {
            publishRateSkipped(dto, "unknown", null, null, "missing payload");
            return Uni.createFrom().voidItem();
        }
        Object rawBrand = p.get("brandSlug");
        Object rawToken = p.get("jesoosToken");
        Object rawSongId = p.get("soundFragmentId");
        Object rawRating = p.get("rating");
        if (rawBrand == null || rawToken == null || rawSongId == null || rawRating == null) {
            publishRateSkipped(dto, rawBrand == null ? "unknown" : rawBrand.toString(),
                    rawSongId == null ? null : rawSongId.toString(),
                    rawToken == null ? null : rawToken.toString(), "missing required payload fields");
            return Uni.createFrom().voidItem();
        }
        int rating = ((Number) rawRating).intValue();
        if (rating != 1 && rating != -1) {
            publishRateSkipped(dto, rawBrand.toString(), rawSongId.toString(), rawToken.toString(), "invalid rating value: " + rating);
            return Uni.createFrom().voidItem();
        }
        String brandSlug = rawBrand.toString();
        UUID soundFragmentId = UUID.fromString(rawSongId.toString());
        String token = rawToken.toString();

        return chatAuthService.authenticateUserFromToken(token)
                .onFailure().recoverWithItem(e -> {
                    publishRateSkipped(dto, brandSlug, soundFragmentId.toString(), token, "invalid token: " + e.getMessage());
                    return null;
                })
                .chain(user -> {
                    if (user == null || user.getId() == 0) {
                        publishRateSkipped(dto, brandSlug, soundFragmentId.toString(), token, "anonymous or unresolved user");
                        return Uni.createFrom().voidItem();
                    }
                    long userId = user.getId();
                    return brandService.getBySlugName(brandSlug)
                            .chain(brand -> {
                                if (brand == null) {
                                    publishRateSkipped(dto, brandSlug, soundFragmentId.toString(), token, "brand not found for slug '" + brandSlug + "'");
                                    return Uni.createFrom().voidItem();
                                }
                                return ratingLogRepository.appendRating(userId, soundFragmentId, brand.getId(), rating)
                                        .invoke(() -> metricPublisher.publishMetric(
                                                brandSlug,
                                                MetricEventType.INFORMATION,
                                                ProcessType.FLOW,
                                                "song_rated",
                                                Map.of(
                                                        "songId", soundFragmentId.toString(),
                                                        "userId", userId,
                                                        "rating", rating
                                                ),
                                                dto.traceId()))
                                        .onFailure().invoke(e -> metricPublisher.publishMetric(
                                                brandSlug,
                                                MetricEventType.ERROR,
                                                ProcessType.FLOW,
                                                "song_rated_failed",
                                                Map.of(
                                                        "songId", soundFragmentId.toString(),
                                                        "userId", userId,
                                                        "rating", rating,
                                                        "error", String.valueOf(e.getMessage())
                                                ),
                                                dto.traceId()));
                            });
                });
    }

    // TODO debug-only: `token` is included in the payload to diagnose why aivox
    // sends a token jesoos never issued. Remove once the token mismatch is fixed.
    private void publishRateSkipped(CommandDTO dto, String brandSlug, String songId, String token, String reason) {
        LOGGER.warnf("Skipping song_rated: %s", reason);
        metricPublisher.publishMetric(
                brandSlug,
                MetricEventType.WARNING,
                ProcessType.FLOW,
                "song_rated_failed",
                Map.of(
                        "songId", songId == null ? "" : songId,
                        "token", token == null ? "" : token,
                        "reason", reason
                ),
                dto.traceId());
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
                            .invoke(brand -> {
                                stream.setAiAgentId(brand.getAiAgentId());
                                LOGGER.infof("Updated DJ agent for brand %s to %s", slug, brand.getAiAgentId());
                            })
                            .replaceWithVoid();
                });
    }

    public Uni<JsonObject> startBrand(String brand) {
        return brandPool.getRadioStream(brand)
                .invoke(stream -> djStateService.activateBoost(brand, 3, BoostType.JINGLE_INTRO))
                .map(this::toResponse)
                .invoke(response -> LOGGER.infof("Start brand %s", brand));
    }

    public Uni<JsonObject> enableDj(String brand) {
        return brandPool.get(brand)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand is not on-line"));
                    } else {
                        djStateService.enableDj(brand);
                        djStateService.activateBoost(brand, 3, BoostType.INTRO);
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
