package com.semantyca.jesoos.service;

import com.semantyca.core.dto.queue.command.CommandDTO;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SharedSongEntry;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.repository.SoundFragmentRatingLogRepository;
import com.semantyca.jesoos.service.agenda.RadioAgendaService;
import com.semantyca.jesoos.service.chat.ChatAuthService;
import com.semantyca.jesoos.service.live.*;
import com.semantyca.jesoos.service.maintenance.DailyAgendaRebuildService;
import com.semantyca.jesoos.service.soundfragment.SharedSoundFragmentService;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.cnst.Boost;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
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
    private final OneTimeStreamPool oneTimeStreamPool;
    private final MetricPublisher metricPublisher;
    private final BrandService brandService;
    private final ChatAuthService chatAuthService;
    private final SoundFragmentRatingLogRepository ratingLogRepository;
    private final DailyAgendaRebuildService dailyAgendaRebuildService;
    private final SharedSoundFragmentService sharedSoundFragmentService;
    private final RadioAgendaService radioAgendaService;

    // Field-injected to avoid enlarging the constructor / a wiring cycle; used to purge ephemeral OTS chat on stop.
    @jakarta.inject.Inject
    com.semantyca.jesoos.service.chat.ChatService chatService;

    @Inject
    public CommandService(DjStateService djStateService, BrandPool brandPool, ScenePool scenePool,
                          StaggeredSongScheduler staggeredSongScheduler,
                          OneTimeStreamService oneTimeStreamService,
                          OtsStreamScheduler otsStreamScheduler,
                          OneTimeStreamPool oneTimeStreamPool,
                          MetricPublisher metricPublisher,
                          BrandService brandService,
                          ChatAuthService chatAuthService,
                          SoundFragmentRatingLogRepository ratingLogRepository,
                          DailyAgendaRebuildService dailyAgendaRebuildService,
                          SharedSoundFragmentService sharedSoundFragmentService,
                          RadioAgendaService radioAgendaService) {
        this.djStateService = djStateService;
        this.brandPool = brandPool;
        this.staggeredSongScheduler = staggeredSongScheduler;
        this.oneTimeStreamService = oneTimeStreamService;
        this.otsStreamScheduler = otsStreamScheduler;
        this.oneTimeStreamPool = oneTimeStreamPool;
        this.metricPublisher = metricPublisher;
        this.brandService = brandService;
        this.chatAuthService = chatAuthService;
        this.ratingLogRepository = ratingLogRepository;
        this.dailyAgendaRebuildService = dailyAgendaRebuildService;
        this.sharedSoundFragmentService = sharedSoundFragmentService;
        this.radioAgendaService = radioAgendaService;
    }

    public Uni<Void> handleQueueCommand(CommandDTO dto) {
        return switch (dto.type()) {
            case FLOW_RESTART -> handleBrandSaved(dto);
            case SONG_RATED -> handleSongRated(dto);
            case REBUILD_AGENDA -> handleRebuildAgenda(dto);
            case JESOOS_START_BRAND -> startBrand(dto.payload().get("brand").toString(), dto.traceId()).replaceWithVoid();
            case JESOOS_START_OTS -> startOts(dto.payload().get("slug").toString(), dto.traceId()).replaceWithVoid();
            case JESOOS_STOP_OTS -> stopOts(dto.payload().get("slug").toString(), dto.traceId()).replaceWithVoid();
            default -> {
                LOGGER.warnf("Ignored queue command: type=%s", dto.type());
                yield Uni.createFrom().voidItem();
            }
        };
    }

    private Uni<Void> handleRebuildAgenda(CommandDTO dto) {
        Map<String, Object> p = dto.payload();
        UUID brandId = UUID.fromString(p.get("brandId").toString());
        return brandService.getById(brandId)
                .chain(brand -> {
                    if (brand == null) {
                        LOGGER.warnf("Brand %s not found, skipping agenda rebuild", brandId);
                        return Uni.createFrom().voidItem();
                    }
                    return brandPool.get(brand.getSlugName())
                            .chain(stream -> {
                                if (stream == null) {
                                    LOGGER.infof("Brand %s not active, skipping agenda rebuild", brand.getSlugName());
                                    return Uni.createFrom().voidItem();
                                }
                                return sharedSoundFragmentService.getPendingPriority(brandId, PlaylistItemType.SONG)
                                        .chain(pending -> {
                                            if (pending.isEmpty()) {
                                                return dailyAgendaRebuildService.rebuildBrandAgenda(brand.getSlugName(), stream)
                                                        .replaceWithVoid();
                                            }
                                            SharedSongEntry priority = pending.getFirst();
                                            if (radioAgendaService.replacePrioritySong(stream, priority)) {
                                                return sharedSoundFragmentService.clearPriorityLabel(priority.soundFragment().getId());
                                            }
                                            LOGGER.infof("No open slot to replace for brand %s, falling back to full rebuild", brand.getSlugName());
                                            return dailyAgendaRebuildService.rebuildBrandAgenda(brand.getSlugName(), stream)
                                                    .replaceWithVoid();
                                        });
                            });
                });
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

    public Uni<JsonObject> startBrand(String brand, UUID traceId) {
        metricPublisher.publishMetric(brand, MetricEventType.COMMAND, ProcessType.FLOW, "brand_start_received", Map.of("brand", brand), traceId);
        return brandPool.getRadioStream(brand)
                .invoke(stream -> djStateService.activateLiveBoost(brand, 3, Boost.SUPER_BOOST))
                .map(this::toResponse)
                .invoke(response -> {
                    LOGGER.infof("Start brand %s", brand);
                    metricPublisher.publishMetric(brand, MetricEventType.INFORMATION, ProcessType.FLOW, "brand_start_ok", Map.of("brand", brand), traceId);
                })
                .onFailure().invoke(e -> metricPublisher.publishMetric(brand, MetricEventType.ERROR, ProcessType.FLOW, "brand_start_failed", Map.of("brand", brand, "error", e.getMessage()), traceId));
    }

    public Uni<JsonObject> enableDj(String brand, UUID traceId) {
        metricPublisher.publishMetric(brand, MetricEventType.COMMAND, ProcessType.FLOW, "enable_dj_received", Map.of("brand", brand), traceId);
        return brandPool.get(brand)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand is not on-line"));
                    } else {
                        djStateService.enableDj(brand);
                        djStateService.activateLiveBoost(brand, 3, Boost.BOOST);
                        LOGGER.infof("DJ enabled for brand: %s (stream already running)", brand);
                        metricPublisher.publishMetric(brand, MetricEventType.INFORMATION, ProcessType.FLOW, "enable_dj_ok", Map.of("brand", brand), traceId);
                        return Uni.createFrom().item(new JsonObject()
                                .put("success", true)
                                .put("brand", brand)
                                .put("djEnabled", true)
                                .put("message", "DJ intros will be generated"));
                    }
                })
                .onFailure().invoke(e -> metricPublisher.publishMetric(brand, MetricEventType.ERROR, ProcessType.FLOW, "enable_dj_failed", Map.of("brand", brand, "error", e.getMessage()), traceId));
    }

    public Uni<JsonObject> disableDj(String brand, UUID traceId) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        metricPublisher.publishMetric(brand, MetricEventType.COMMAND, ProcessType.FLOW, "disable_dj_received", Map.of("brand", brand), traceId);
        return Uni.createFrom().item(() -> {
            djStateService.disableDj(brand);
            metricPublisher.publishMetric(brand, MetricEventType.INFORMATION, ProcessType.FLOW, "disable_dj_ok", Map.of("brand", brand), traceId);
            return new JsonObject()
                    .put("success", true)
                    .put("brand", brand)
                    .put("djEnabled", false)
                    .put("message", "DJ intros disabled, songs only mode");
        }).onFailure().invoke(e -> metricPublisher.publishMetric(brand, MetricEventType.ERROR, ProcessType.FLOW, "disable_dj_failed", Map.of("brand", brand, "error", e.getMessage()), traceId));
    }

    public Uni<JsonObject> backpressure(String brand, UUID traceId) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        metricPublisher.publishMetric(brand, MetricEventType.COMMAND, ProcessType.FLOW, "backpressure_received", Map.of("brand", brand), traceId);
        return oneTimeStreamPool.get(brand)
                .chain(otsStream -> {
                    if (otsStream != null) {
                        // OtsStreamScheduler has no skip mechanism — it schedules every entry
                        // up front from absolute wall-clock times, so a skip-counter increment
                        // consumed by StaggeredSongScheduler (radio only) would be a silent no-op.
                        metricPublisher.publishMetric(brand, MetricEventType.WARNING, ProcessType.FLOW, "backpressure_ignored_ots",
                                Map.of("brand", brand, "reason", "OTS streams are not skip-aware"), traceId);
                        return Uni.createFrom().item(new JsonObject()
                                .put("success", false)
                                .put("brand", brand)
                                .put("message", "Backpressure ignored: OTS streams are not skip-aware"));
                    }
                    int pending = staggeredSongScheduler.backpressure(brand);
                    metricPublisher.publishMetric(brand, MetricEventType.INFORMATION, ProcessType.FLOW, "backpressure_ok", Map.of("brand", brand, "pendingSkips", pending), traceId);
                    return Uni.createFrom().item(new JsonObject()
                            .put("success", true)
                            .put("brand", brand)
                            .put("pendingSkips", pending));
                })
                .onFailure().invoke(e -> metricPublisher.publishMetric(brand, MetricEventType.ERROR, ProcessType.FLOW, "backpressure_failed", Map.of("brand", brand, "error", e.getMessage()), traceId));
    }

    public Uni<Boolean> getDjStatus(String brand) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }

        return Uni.createFrom().item(() -> djStateService.isDjEnabled(brand));
    }

    public Uni<JsonObject> stopBrand(String brand, UUID traceId) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        metricPublisher.publishMetric(brand, MetricEventType.COMMAND, ProcessType.FLOW, "brand_stop_received", Map.of("brand", brand), traceId);
        djStateService.disableDj(brand);
        return brandPool.stopAndRemove(brand)
                .map(stoppedAgenda -> {
                    if (stoppedAgenda != null) {
                        LOGGER.infof("Stopped brand: %s, status: %s", brand, stoppedAgenda.getStatus());
                        metricPublisher.publishMetric(brand, MetricEventType.INFORMATION, ProcessType.FLOW, "brand_stop_ok", Map.of("brand", brand, "status", stoppedAgenda.getStatus().name()), traceId);
                        return new JsonObject()
                                .put("success", true)
                                .put("brand", brand)
                                .put("status", stoppedAgenda.getStatus().name())
                                .put("message", "Brand stopped and removed from pool");
                    } else {
                        LOGGER.warnf("Brand %s not found in pool", brand);
                        metricPublisher.publishMetric(brand, MetricEventType.WARNING, ProcessType.FLOW, "brand_stop_not_found", Map.of("brand", brand), traceId);
                        return new JsonObject()
                                .put("success", false)
                                .put("brand", brand)
                                .put("message", "Brand not found in pool");
                    }
                })
                .onFailure().invoke(e -> metricPublisher.publishMetric(brand, MetricEventType.ERROR, ProcessType.FLOW, "brand_stop_failed", Map.of("brand", brand, "error", e.getMessage()), traceId));
    }

    public Uni<JsonObject> emitTimelineEntry(String brand, UUID sceneId, int sequenceNumber, UUID traceId) {
        if (brand == null || brand.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing brand parameter"));
        }
        if (sceneId == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Missing sceneId parameter"));
        }
        metricPublisher.publishMetric(brand, MetricEventType.COMMAND, ProcessType.FLOW, "emit_timeline_entry_received",
                Map.of("brand", brand, "sceneId", sceneId.toString(), "sequenceNumber", sequenceNumber), traceId);
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

                    return staggeredSongScheduler.emitTimelineEntry(brand, scene, entry, scene.getTimeZone(), StreamPriority.PRIORITIZED_FRONT.getValue())
                            .map(v -> {
                                metricPublisher.publishMetric(brand, MetricEventType.INFORMATION, ProcessType.FLOW, "emit_timeline_entry_ok",
                                        Map.of("brand", brand, "sceneId", sceneId.toString(), "sceneTitle", scene.getSceneTitle(), "sequenceNumber", sequenceNumber), traceId);
                                return new JsonObject()
                                        .put("success", true)
                                        .put("brand", brand)
                                        .put("sceneId", sceneId.toString())
                                        .put("sceneTitle", scene.getSceneTitle())
                                        .put("sequenceNumber", sequenceNumber)
                                        .put("message", "Timeline entry emitted");
                            });
                })
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.errorf(failure, "Failed to emit timeline entry #%d from scene %s for brand: %s", sequenceNumber, sceneId, brand);
                    metricPublisher.publishMetric(brand, MetricEventType.ERROR, ProcessType.FLOW, "emit_timeline_entry_failed",
                            Map.of("brand", brand, "sceneId", sceneId.toString(), "sequenceNumber", sequenceNumber, "error", failure.getMessage()), traceId);
                    return new JsonObject()
                            .put("success", false)
                            .put("brand", brand)
                            .put("sceneId", sceneId.toString())
                            .put("sequenceNumber", sequenceNumber)
                            .put("error", failure.getMessage());
                });
    }

    public Uni<JsonObject> startOts(String otsSlugName, UUID traceId) {
        metricPublisher.publishMetric(otsSlugName, MetricEventType.COMMAND, ProcessType.FLOW, "ots_start_received", Map.of("otsSlugName", otsSlugName), traceId);
        return oneTimeStreamService.start(otsSlugName)
                .map(stream -> {
                    metricPublisher.publishMetric(otsSlugName, MetricEventType.INFORMATION, ProcessType.FLOW, "ots_start_ok", Map.of("otsSlugName", otsSlugName, "status", stream.getStatus().name()), traceId);
                    return new JsonObject()
                            .put("success", true)
                            .put("otsSlugName", stream.getSlugName())
                            .put("status", stream.getStatus().name())
                            .put("message", "OTS stream started");
                })
                .onFailure().invoke(e -> metricPublisher.publishMetric(otsSlugName, MetricEventType.ERROR, ProcessType.FLOW, "ots_start_failed", Map.of("otsSlugName", otsSlugName, "error", e.getMessage()), traceId));
    }

    public Uni<JsonObject> stopOts(String otsSlugName, UUID traceId) {
        metricPublisher.publishMetric(otsSlugName, MetricEventType.COMMAND, ProcessType.FLOW, "ots_stop_received", Map.of("otsSlugName", otsSlugName), traceId);
        return Uni.createFrom().voidItem()
                .invoke(() -> {
                    otsStreamScheduler.cancelOtsTimers(otsSlugName);
                    chatService.purgeOtsChat(otsSlugName);
                })
                .chain(() -> oneTimeStreamPool.stopAndRemove(otsSlugName))
                .map(stream -> {
                    metricPublisher.clearTracking(otsSlugName);
                    metricPublisher.publishMetric(otsSlugName, MetricEventType.INFORMATION, ProcessType.FLOW, "ots_stop_ok", Map.of("otsSlugName", otsSlugName), traceId);
                    return new JsonObject()
                            .put("success", true)
                            .put("otsSlugName", otsSlugName)
                            .put("message", "OTS stream stopped");
                })
                .onFailure().invoke(e -> metricPublisher.publishMetric(otsSlugName, MetricEventType.ERROR, ProcessType.FLOW, "ots_stop_failed", Map.of("otsSlugName", otsSlugName, "error", e.getMessage()), traceId));
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
