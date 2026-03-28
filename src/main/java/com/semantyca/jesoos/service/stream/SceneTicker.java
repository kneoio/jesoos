package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.model.stream.TimelineEntryStatus;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class SceneTicker {
    private static final Logger LOGGER = Logger.getLogger(SceneTicker.class);

    @Inject
    ScenePool scenePool;

    @Inject
    StaggeredSongScheduler staggeredSongScheduler;

    @Scheduled(every = "10s",  delay = 5, delayUnit = TimeUnit.SECONDS)
    void scheduleActiveScenes() {
        scenePool.getActiveScenes().forEach((brandName, scene) -> {
            if (scene != null && scene.getTimeline() != null && !scene.getTimeline().isEmpty()) {
                boolean hasUnscheduledEntries = scene.getTimeline().stream()
                        .anyMatch(entry -> entry.getStatus() == TimelineEntryStatus.PENDING);

                if (hasUnscheduledEntries) {
                    List<Integer> pendingSeqs = scene.getTimeline().stream()
                            .filter(e -> e.getStatus() == TimelineEntryStatus.PENDING)
                            .map(TimelineEntry::getSequenceNumber)
                            .toList();
                    staggeredSongScheduler.cancelPending(brandName, pendingSeqs);
                    staggeredSongScheduler.scheduleSceneSongs(brandName, scene);
                }
            } else {
                LOGGER.infof("Scene %s for brand %s is not active. Skipping.", scene.getSceneTitle(), brandName);
            }
        });
    }
}
