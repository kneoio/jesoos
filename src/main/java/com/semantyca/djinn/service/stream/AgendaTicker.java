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
    BrandPool brandPool;

    @Inject
    QueueSupplier queueSupplier;

    @Scheduled(every = "10s")
    void tick() {
        Map<String, StreamAgenda> agendas = brandPool.getAll();
        if (agendas.isEmpty()) {
            return;
        }

        agendas.forEach((key, agenda) -> {
            String[] parts = key.split(":", 2);
            String brand = parts[0];
            ZoneId zoneId = agenda.getTimeZone() != null ? agenda.getTimeZone() : ZoneId.of("UTC");
            LocalDateTime nowLocal = ZonedDateTime.now(zoneId).toLocalDateTime();

            agenda.getLiveScenes().forEach(scene -> {
                if (scene.getScheduledStartTime() == null) return;
                if (scene.getSentToQueueAt() != null) return;

                LocalDateTime start = scene.getScheduledStartTime();
                LocalDateTime end = start.plusSeconds(scene.getDurationSeconds());
                boolean withinWindow = !nowLocal.isBefore(start) && nowLocal.isBefore(end);
                LOGGER.infof("Checking scene: %s, start: %s, end: %s, nowLocal: %s",
                        scene.getSceneTitle(), start, end, nowLocal);
                if (!withinWindow) return;

                long lagSeconds = Duration.between(start, nowLocal).getSeconds();
                FireReason reason = lagSeconds < 30 ? FireReason.ON_TIME : FireReason.REBOOT;
                processScene(brand, key, scene, reason);
            });
        });
    }

    private void processScene(String brand, String agendaKey, LiveScene scene, FireReason reason) {
        LOGGER.infof("Processing scene '%s' for brand: %s, agenda: %s, reason: %s",
                scene.getSceneTitle(), brand, agendaKey, reason);
        scene.setSentToQueueAt(LocalDateTime.now());
        scene.setFireReason(reason);

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