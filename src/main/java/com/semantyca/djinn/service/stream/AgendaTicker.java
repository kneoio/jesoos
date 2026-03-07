package com.semantyca.djinn.service.stream;

import com.semantyca.djinn.messaging.QueueSupplier;
import com.semantyca.djinn.model.stream.LiveScene;
import com.semantyca.djinn.model.stream.PendingSongEntry;
import com.semantyca.djinn.model.stream.StreamAgenda;
import com.semantyca.mixpla.dto.queue.AddToQueueDTO;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

@ApplicationScoped
public class AgendaTicker {
    private static final Logger LOGGER = Logger.getLogger(AgendaTicker.class);

    @Inject
    StreamAgendaManager agendaManager;

    @Inject
    QueueSupplier queueSupplier;

    @Scheduled(every = "10s")
    void tick() {
        Map<String, StreamAgenda> agendas = agendaManager.getAll();
        if (agendas.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        agendas.forEach((key, agenda) -> {
            String[] parts = key.split(":", 2);
            String brand = parts[0];
            ZoneId zoneId = agenda.getTimeZone() != null ? agenda.getTimeZone() : ZoneId.of("UTC");
            ZonedDateTime nowInZone = ZonedDateTime.now(zoneId);
            LocalDateTime nowLocal = nowInZone.toLocalDateTime();

            agenda.getLiveScenes().forEach(scene -> {
                if (isSceneDue(scene, nowLocal)) {
                    processScene(brand, key, scene);
                }
            });
        });
    }

    private boolean isSceneDue(LiveScene scene, LocalDateTime now) {
        LocalDateTime startTime = scene.getScheduledStartTime();
        if (startTime == null) {
            return false;
        }

        // Check if now is within 10 seconds of scene start time
        long secondsDiff = Math.abs(Duration.between(now, startTime).getSeconds());
        return secondsDiff < 10;
    }

    private void processScene(String brand, String agendaKey, LiveScene scene) {
        LOGGER.infof("Processing scene '%s' for brand: %s, agenda: %s", scene.getSceneTitle(), brand, agendaKey);
        scene.setSentToQueueAt(LocalDateTime.now());

        for (PendingSongEntry songEntry : scene.getSongs()) {
            AddToQueueDTO dto = new AddToQueueDTO();
            // TODO: Populate AddToQueueDTO fields based on songEntry

            String uploadId = scene.getSceneId() + ":" + songEntry.getSoundFragment().getId() + ":" + System.currentTimeMillis();

            queueSupplier.sendToQueue(brand, dto, uploadId)
                    .subscribe()
                    .with(
                            success -> LOGGER.infof("Queued song for brand: %s, scene: %s, song: %s",
                                    brand, scene.getSceneTitle(), songEntry.getSoundFragment().getTitle()),
                            failure -> LOGGER.errorf("Failed to queue song for brand: %s, scene: %s, error: %s",
                                    brand, scene.getSceneTitle(), failure.getMessage())
                    );
        }
    }
}
