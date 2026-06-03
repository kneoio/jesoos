package com.semantyca.jesoos.service.live;

import com.semantyca.core.model.scheduler.OnceTrigger;
import com.semantyca.core.model.scheduler.Task;
import com.semantyca.core.model.cnst.TriggerType;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PromptEntry;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.repository.prompt.PromptRepository;
import com.semantyca.jesoos.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.mixpla.model.DjPrompt;
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
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PrerecordedFragmentTicker {
    private static final Logger LOGGER = Logger.getLogger(PrerecordedFragmentTicker.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final BrandPool brandPool;
    private final SoundFragmentRepository soundFragmentRepository;
    private final PromptRepository promptRepository;
    private final StaggeredSongScheduler staggeredSongScheduler;
    private final Random random = new Random();

    private final ConcurrentHashMap<String, Set<String>> firedKeys = new ConcurrentHashMap<>();

    @Inject
    public PrerecordedFragmentTicker(BrandPool brandPool,
                                     SoundFragmentRepository soundFragmentRepository,
                                     PromptRepository promptRepository,
                                     StaggeredSongScheduler staggeredSongScheduler) {
        this.brandPool = brandPool;
        this.soundFragmentRepository = soundFragmentRepository;
        this.promptRepository = promptRepository;
        this.staggeredSongScheduler = staggeredSongScheduler;
    }

    @Scheduled(every = "60s", delay = 10, delayUnit = java.util.concurrent.TimeUnit.SECONDS)
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
        List<SoundFragment> due = fragments.stream()
                .filter(sf -> isDue(sf, zone, now) && !alreadyFired(brandSlug, sf.getId(), now))
                .toList();

        if (due.isEmpty()) return Uni.createFrom().voidItem();

        return Uni.join().all(due.stream().map(sf -> fireFragment(brandSlug, stream, sf, zone, now)).toList())
                .andCollectFailures()
                .replaceWithVoid();
    }

    private Uni<Void> fireFragment(String brandSlug, ILiveStream stream, SoundFragment fragment, ZoneId zone, ZonedDateTime now) {
        PromptType promptType = fragment.getType() == PlaylistItemType.PRERECORDED_ADVERTISEMENT
                ? PromptType.ADVERTISEMENT_INTRO
                : PromptType.PODCAST_INTRO;

        PromptFilter filter = new PromptFilter();
        filter.setPromptType(promptType);
        filter.setActivated(true);

        return promptRepository.getAll(100, 0, SuperUser.build(), filter)
                .chain(prompts -> {
                    if (prompts.isEmpty()) {
                        LOGGER.warnf("No %s prompt found, skipping fragment '%s' for brand '%s'", promptType, fragment.getSlugName(), brandSlug);
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
                    scene.setSceneTitle("scheduled-" + fragment.getType().name().toLowerCase());
                    scene.setTimeZone(zone);
                    scene.setAgentId(stream.getAiAgentId());
                    scene.setTraceId(UUID.randomUUID());
                    scene.setTimeline(List.of(entry));

                    markFired(brandSlug, fragment.getId(), now);
                    LOGGER.infof("Firing scheduled %s fragment '%s' for brand '%s' with prompt '%s'",
                            fragment.getType(), fragment.getSlugName(), brandSlug, prompt.getTitle());

                    return staggeredSongScheduler.emitTimelineEntry(brandSlug, scene, entry, zone, StreamPriority.PRIORITIZED_FRONT.getValue());
                });
    }

    private boolean isDue(SoundFragment fragment, ZoneId zone, ZonedDateTime now) {
        if (fragment.getScheduler() == null || fragment.getScheduler().getTasks() == null) return false;
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
                return true;
            }
        }
        return false;
    }

    private boolean alreadyFired(String brandSlug, UUID fragmentId, ZonedDateTime now) {
        String key = fragmentId + ":" + now.toLocalDate() + ":" + now.toLocalTime().withSecond(0).withNano(0).format(TIME_FORMAT);
        Set<String> brandKeys = firedKeys.computeIfAbsent(brandSlug, k -> ConcurrentHashMap.newKeySet());
        return !brandKeys.add(key);
    }

    private void markFired(String brandSlug, UUID fragmentId, ZonedDateTime now) {
        String key = fragmentId + ":" + now.toLocalDate() + ":" + now.toLocalTime().withSecond(0).withNano(0).format(TIME_FORMAT);
        firedKeys.computeIfAbsent(brandSlug, k -> ConcurrentHashMap.newKeySet()).add(key);
    }
}
