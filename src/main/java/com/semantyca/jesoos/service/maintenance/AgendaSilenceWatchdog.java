package com.semantyca.jesoos.service.maintenance;

import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary self-heal: if a live brand has produced no emission for too long, force an agenda
 * rebuild so the listener does not hear silence. This MASKS the underlying stall (it does not
 * diagnose why emission stopped) — it is a safety net, not a fix.
 */
@ApplicationScoped
public class AgendaSilenceWatchdog {
    private static final Logger LOGGER = Logger.getLogger(AgendaSilenceWatchdog.class);
    /** Force a rebuild once a brand has been silent at least this long. */
    private static final long SILENCE_REBUILD_SECONDS = 300;
    /** Minimum gap between forced rebuilds for the same brand, to avoid rebuild storms. */
    private static final long REBUILD_COOLDOWN_SECONDS = 300;

    private final BrandPool brandPool;
    private final MetricPublisher metricPublisher;
    private final DailyAgendaRebuildService dailyAgendaRebuildService;
    private final ConcurrentHashMap<String, Instant> lastRebuildAt = new ConcurrentHashMap<>();

    @Inject
    public AgendaSilenceWatchdog(BrandPool brandPool,
                                 MetricPublisher metricPublisher,
                                 DailyAgendaRebuildService dailyAgendaRebuildService) {
        this.brandPool = brandPool;
        this.metricPublisher = metricPublisher;
        this.dailyAgendaRebuildService = dailyAgendaRebuildService;
    }

    @Scheduled(every = "60s")
    void check() {
        Instant now = Instant.now();
        for (ILiveStream stream : brandPool.getStationsSnapshot()) {
            String slug = stream.getSlugName();
            long silentSeconds = metricPublisher.secondsSinceExpectedEmit(slug);
            if (silentSeconds < SILENCE_REBUILD_SECONDS) {
                continue;
            }
            Instant last = lastRebuildAt.get(slug);
            if (last != null && last.isAfter(now.minusSeconds(REBUILD_COOLDOWN_SECONDS))) {
                continue;
            }
            lastRebuildAt.put(slug, now);
            LOGGER.errorf("Brand '%s' silent for %ds — forcing agenda rebuild (temporary self-heal)", slug, silentSeconds);
            metricPublisher.publishMetric(slug, MetricEventType.ERROR, ProcessType.CRON,
                    "silence_recovery_rebuild", Map.of("silentSeconds", silentSeconds));
            dailyAgendaRebuildService.rebuildBrandAgenda(slug, stream)
                    .subscribe().with(
                            n -> LOGGER.infof("Silence rebuild done for brand '%s'", slug),
                            err -> LOGGER.errorf(err, "Silence rebuild failed for brand '%s'", slug));
        }
    }
}
