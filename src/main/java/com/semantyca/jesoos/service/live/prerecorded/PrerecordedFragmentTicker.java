package com.semantyca.jesoos.service.live.prerecorded;

import com.semantyca.core.model.scheduler.OnceTrigger;
import com.semantyca.core.model.scheduler.PeriodicTrigger;
import com.semantyca.core.model.scheduler.Task;
import com.semantyca.core.model.cnst.TriggerType;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PromptEntry;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.messaging.MetricPublisher;
import com.semantyca.jesoos.repository.prompt.PromptRepository;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.jesoos.service.live.BrandPool;
import com.semantyca.jesoos.service.live.StaggeredSongScheduler;
import com.semantyca.mixpla.model.DjPrompt;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.dto.queue.metric.ProcessType;
import com.semantyca.mixpla.model.cnst.MixingType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.PromptType;
import com.semantyca.mixpla.model.cnst.StreamPriority;
import com.semantyca.mixpla.model.filter.PromptFilter;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class PrerecordedFragmentTicker {
    private static final Logger LOGGER = Logger.getLogger(PrerecordedFragmentTicker.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final BrandPool brandPool;
    private final SoundFragmentRepository soundFragmentRepository;
    private final PromptRepository promptRepository;
    private final StaggeredSongScheduler staggeredSongScheduler;
    private final MetricPublisher metricPublisher;
    private final Random random = new Random();

    private final ConcurrentHashMap<String, Set<String>> firedKeys = new ConcurrentHashMap<>();

    @Inject
    public PrerecordedFragmentTicker(BrandPool brandPool,
                                     SoundFragmentRepository soundFragmentRepository,
                                     PromptRepository promptRepository,
                                     StaggeredSongScheduler staggeredSongScheduler,
                                     MetricPublisher metricPublisher) {
        this.brandPool = brandPool;
        this.soundFragmentRepository = soundFragmentRepository;
        this.promptRepository = promptRepository;
        this.staggeredSongScheduler = staggeredSongScheduler;
        this.metricPublisher = metricPublisher;
    }

    @Scheduled(every = "60s", delay = 10, delayUnit = TimeUnit.SECONDS)
    void tick() {
        Collection<ILiveStream> streams = brandPool.getStationsSnapshot();
        for (ILiveStream stream : streams) {
            String brandSlug = stream.getSlugName();
            UUID brandId = stream.getMasterBrandId();
            ZoneId zone = stream.getTimeZone();
            ZonedDateTime now = ZonedDateTime.now(zone);

            soundFragmentRepository.findActiveScheduledByBrand(brandId)
                    .chain(fragments -> processFragments(brandSlug, stream, fragments, zone, now))
                    .subscribe().with(
                            v -> {},
                            err -> LOGGER.errorf("PrerecordedFragmentTicker failed for brand '%s': %s", brandSlug, err.getMessage())
                    );
        }
    }

    private Uni<Void> processFragments(String brandSlug, ILiveStream stream, List<SoundFragment> fragments, ZoneId zone, ZonedDateTime now) {
        record DueFragment(SoundFragment sf, Task matchedTask) {}

        List<DueFragment> due = fragments.stream()
                .map(sf -> {
                    Task t = matchedTask(sf, zone, now);
                    return t != null && !alreadyFired(brandSlug, sf.getId(), now) ? new DueFragment(sf, t) : null;
                })
                .filter(Objects::nonNull)
                .toList();

        if (due.isEmpty()) return Uni.createFrom().voidItem();

        return Uni.join().all(due.stream().map(d -> fireFragment(brandSlug, stream, d.sf(), zone, now, d.matchedTask())).toList())
                .andCollectFailures()
                .replaceWithVoid();
    }

    private Uni<Void> fireFragment(String brandSlug, ILiveStream stream, SoundFragment fragment, ZoneId zone, ZonedDateTime now, Task matchedTask) {
        PromptType promptType = fragment.getType() == PlaylistItemType.PRERECORDED_ADVERTISEMENT
                ? PromptType.ADVERTISEMENT_INTRO
                : PromptType.PODCAST_INTRO;

        PromptFilter filter = new PromptFilter();
        filter.setPromptType(promptType);
        filter.setActivated(true);

        UUID traceId = UUID.randomUUID();
        Map<String, Object> startedPayload = new java.util.LinkedHashMap<>();
        startedPayload.put("sf", fragment.getTitle());
        startedPayload.put("promptType", promptType.name());
        if (matchedTask.getTriggerType() == TriggerType.PERIODIC && matchedTask.getPeriodicTrigger() != null) {
            LocalTime nextAt = now.toLocalTime().withSecond(0).withNano(0).plusMinutes(matchedTask.getPeriodicTrigger().getInterval());
            startedPayload.put("nextAt", nextAt.format(TIME_FORMAT));
        }
        metricPublisher.publishMetric(brandSlug, MetricEventType.INFORMATION, ProcessType.FLOW,
                "prerecorded_flow_started", startedPayload, traceId);

        return promptRepository.getAll(100, 0, SuperUser.build(), filter)
                .chain(prompts -> {
                    if (prompts.isEmpty()) {
                        LOGGER.errorf("No %s prompt found for prerecorded, for brand '%s'", promptType, brandSlug);
                        metricPublisher.publishMetric(brandSlug, MetricEventType.WARNING, ProcessType.FLOW,
                                "prerecorded_fragment_skipped_no_prompt",
                                Map.of("fragmentSlug", fragment.getSlugName(), "promptType", promptType.name()), traceId);
                        return Uni.createFrom().voidItem();
                    }
                    DjPrompt prompt = prompts.get(random.nextInt(prompts.size()));

                    PromptEntry promptEntry = new PromptEntry();
                    promptEntry.setPromptId(prompt.getId());

                    SongEntry songEntry = new SongEntry(fragment, promptEntry, 0);

                    LocalDateTime emissionTime = now.toLocalDateTime();
                    TimelineEntry entry = new TimelineEntry(0, emissionTime, List.of(songEntry), MixingType.INTRO_SONG, true, false);
                    LiveScene scene = new LiveScene();
                    scene.setSceneId(UUID.randomUUID());
                    scene.setSceneTitle("prerecorded-scheduled-" + fragment.getType().name().toLowerCase());
                    scene.setTimeZone(zone);
                    scene.setAgentId(stream.getAiAgentId());
                    scene.setTraceId(traceId);
                    scene.setTimeline(List.of(entry));
                    scene.setMixingType(MixingType.INTRO_SONG);

                    LOGGER.infof("Firing scheduled %s fragment '%s' for brand '%s' with intro prompt '%s'",
                            fragment.getType(), fragment.getSlugName(), brandSlug, prompt.getTitle());
                    metricPublisher.publishMetric(brandSlug, MetricEventType.INFORMATION, ProcessType.FLOW,
                            "prerecorded_firing",
                            Map.of("scene", scene.getSceneTitle(), "promptType", promptType.name()),traceId);

                    return staggeredSongScheduler.emitTimelineEntry(brandSlug, scene, entry, zone, StreamPriority.PRIORITIZED_FRONT.getValue(), traceId)
                            .invoke(() -> markFired(brandSlug, fragment.getId(), now));
                });
    }

    private Task matchedTask(SoundFragment fragment, ZoneId zone, ZonedDateTime now) {
        if (fragment.getScheduler() == null || fragment.getScheduler().getTasks() == null) return null;
        LocalTime nowTime = now.toLocalTime().withSecond(0).withNano(0);
        DayOfWeek today = now.getDayOfWeek();

        for (Task task : fragment.getScheduler().getTasks()) {
            if (task.getTriggerType() == TriggerType.ONCE && task.getOnceTrigger() != null) {
                OnceTrigger trigger = task.getOnceTrigger();
                if (trigger.getStartTime() == null) continue;
                LocalTime triggerTime;
                try {
                    triggerTime = LocalTime.parse(trigger.getStartTime(), TIME_FORMAT);
                } catch (Exception e) {
                    LOGGER.warnf("Cannot parse startTime '%s' for fragment '%s'", trigger.getStartTime(), fragment.getSlugName());
                    continue;
                }
                if (!nowTime.equals(triggerTime)) continue;
                if (trigger.getWeekdays() != null && !trigger.getWeekdays().isEmpty()
                        && !trigger.getWeekdays().contains(today.name())) continue;
                return task;
            } else if (task.getTriggerType() == TriggerType.PERIODIC && task.getPeriodicTrigger() != null) {
                PeriodicTrigger trigger = task.getPeriodicTrigger();
                if (trigger.getStartTime() == null || trigger.getEndTime() == null || trigger.getInterval() <= 0) continue;
                LocalTime startTime, endTime;
                try {
                    startTime = LocalTime.parse(trigger.getStartTime(), TIME_FORMAT);
                    endTime = LocalTime.parse(trigger.getEndTime(), TIME_FORMAT);
                } catch (Exception e) {
                    LOGGER.warnf("Cannot parse periodic trigger times for fragment '%s'", fragment.getSlugName());
                    continue;
                }
                if (trigger.getWeekdays() != null && !trigger.getWeekdays().isEmpty()
                        && !trigger.getWeekdays().contains(today.name())) continue;
                if (nowTime.isBefore(startTime) || nowTime.isAfter(endTime)) continue;
                long minutesSinceStart = ChronoUnit.MINUTES.between(startTime, nowTime);
                if (minutesSinceStart % trigger.getInterval() != 0) continue;
                return task;
            }
        }
        return null;
    }

    private boolean alreadyFired(String brandSlug, UUID fragmentId, ZonedDateTime now) {
        String key = fragmentId + ":" + now.toLocalDate() + ":" + now.toLocalTime().withSecond(0).withNano(0).format(TIME_FORMAT);
        Set<String> brandKeys = firedKeys.get(brandSlug);
        return brandKeys != null && brandKeys.contains(key);
    }

    private void markFired(String brandSlug, UUID fragmentId, ZonedDateTime now) {
        String key = fragmentId + ":" + now.toLocalDate() + ":" + now.toLocalTime().withSecond(0).withNano(0).format(TIME_FORMAT);
        firedKeys.computeIfAbsent(brandSlug, k -> ConcurrentHashMap.newKeySet()).add(key);
    }
}
