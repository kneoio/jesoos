package com.semantyca.jesoos.repository.agenda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.repository.AsyncRepository;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AgendaRepository extends AsyncRepository {

    private static final String TABLE_CONFIGURATIONS = "mixpla__agenda_configurations";
    private static final String TABLE_SCENES = "mixpla__agenda_scenes";
    private static final String TABLE_TIMELINE_EVENTS = "mixpla__timeline_events";
    private static final String TABLE_SONGS = "mixpla__agenda_songs";
    private static final String TABLE_EVENT_SONGS = "mixpla__timeline_event_songs";

    @Inject
    public AgendaRepository(Pool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<UUID> save(StreamAgenda agenda, UUID brandId, long authorId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID configId = UUID.randomUUID();

        String configSql = "INSERT INTO " + TABLE_CONFIGURATIONS +
                " (id, author, reg_date, last_mod_user, last_mod_date, timezone, country, created_at, total_scenes, brand_id) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)";

        String timezone = agenda.getTimeZone() != null ? agenda.getTimeZone().getId() : "UTC";

        OffsetDateTime createdAt = agenda.getCreatedAt() != null
                ? agenda.getCreatedAt().atOffset(ZoneOffset.UTC)
                : now;

        Tuple configParams = Tuple.of(configId)
                .addLong(authorId)
                .addOffsetDateTime(now)
                .addLong(authorId)
                .addOffsetDateTime(now)
                .addString(timezone)
                .addString("XX")
                .addOffsetDateTime(createdAt)
                .addInteger(agenda.getTotalScenes())
                .addUUID(brandId);

        return client.withTransaction(tx ->
                tx.preparedQuery(configSql)
                        .execute(configParams)
                        .onItem().transformToUni(ignored -> saveScenes(tx, configId, agenda.getLiveScenes()))
                        .onItem().transform(ignored -> configId)
        );
    }

    private Uni<Void> saveScenes(SqlClient tx, UUID configId, List<LiveScene> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<Uni<Void>> sceneUnis = new ArrayList<>();
        for (LiveScene scene : scenes) {
            UUID sceneId = UUID.randomUUID();
            OffsetDateTime firstEmission = scene.getActualStartTime() != null
                    ? scene.getActualStartTime().atOffset(ZoneOffset.UTC)
                    : OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime lastEmission = scene.getActualEndTime() != null
                    ? scene.getActualEndTime().atOffset(ZoneOffset.UTC)
                    : firstEmission.plusSeconds(scene.getDurationSeconds());

            String sceneSql = "INSERT INTO " + TABLE_SCENES +
                    " (id, configuration_id, title, first_emission_time, last_emission_time, duration_seconds, total_songs, timeline_built, fit_seconds) " +
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)";

            Tuple sceneParams = Tuple.of(sceneId, configId)
                    .addString(scene.getSceneTitle() != null ? scene.getSceneTitle() : "")
                    .addOffsetDateTime(firstEmission)
                    .addOffsetDateTime(lastEmission)
                    .addInteger(scene.getDurationSeconds())
                    .addInteger(countSongs(scene))
                    .addBoolean(scene.isTimelineBuild())
                    .addInteger(scene.getFitSeconds());

            Uni<Void> sceneUni = tx.preparedQuery(sceneSql)
                    .execute(sceneParams)
                    .onItem().transformToUni(ignored ->
                            saveTimelineEvents(tx, sceneId, scene.getTimeline())
                    );
            sceneUnis.add(sceneUni);
        }

        return Uni.join().all(sceneUnis).andFailFast().replaceWithVoid();
    }

    private Uni<Void> saveTimelineEvents(SqlClient tx, UUID sceneId, List<TimelineEntry> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<Uni<Void>> eventUnis = new ArrayList<>();
        for (TimelineEntry entry : timeline) {
            UUID eventId = entry.getId() != null ? entry.getId() : UUID.randomUUID();

            String eventSql = "INSERT INTO " + TABLE_TIMELINE_EVENTS +
                    " (id, scene_id, sequence_number, scheduled_emission_time, duration_seconds, mixing_strategy, has_intro, has_jingle, generated, batch_id, status) " +
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)";

            OffsetDateTime scheduledTime = entry.getScheduledEmissionTime() != null
                    ? entry.getScheduledEmissionTime().atOffset(ZoneOffset.UTC)
                    : OffsetDateTime.now(ZoneOffset.UTC);

            Tuple eventParams = Tuple.of(eventId, sceneId)
                    .addInteger(entry.getSequenceNumber())
                    .addOffsetDateTime(scheduledTime)
                    .addInteger(entry.getEstimatedDurationSeconds())
                    .addString(entry.getMixingStrategy() != null ? entry.getMixingStrategy().name() : "SONG_ONLY")
                    .addBoolean(entry.isHasIntro())
                    .addBoolean(entry.isHasJingle())
                    .addBoolean(entry.isGenerated())
                    .addInteger(0)
                    .addString(entry.getStatus() != null ? entry.getStatus().name() : "PENDING");

            Uni<Void> eventUni = tx.preparedQuery(eventSql)
                    .execute(eventParams)
                    .onItem().transformToUni(ignored ->
                            saveSongsForEvent(tx, eventId, entry.getSongs())
                    );
            eventUnis.add(eventUni);
        }

        return Uni.join().all(eventUnis).andFailFast().replaceWithVoid();
    }

    private Uni<Void> saveSongsForEvent(SqlClient tx, UUID eventId, List<SongEntry> songs) {
        if (songs == null || songs.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<Uni<Void>> songUnis = new ArrayList<>();
        for (SongEntry songEntry : songs) {
            if (songEntry.getSoundFragment() == null) continue;

            UUID songId = songEntry.getSoundFragment().getId();
            String title = songEntry.getSoundFragment().getTitle();
            String artist = songEntry.getSoundFragment().getArtist();
            int durationSec = songEntry.getDurationSeconds();

            String upsertSongSql = "INSERT INTO " + TABLE_SONGS +
                    " (id, title, artist, duration_seconds, language) " +
                    "VALUES ($1, $2, $3, $4, $5) ON CONFLICT (id) DO NOTHING";

            Tuple songParams = Tuple.of(songId, title != null ? title : "", artist != null ? artist : "", durationSec, "xx");

            String bridgeSql = "INSERT INTO " + TABLE_EVENT_SONGS +
                    " (timeline_event_id, song_id, play_order) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING";

            Tuple bridgeParams = Tuple.of(eventId, songId, songEntry.getSequenceNumber());

            Uni<Void> songUni = tx.preparedQuery(upsertSongSql)
                    .execute(songParams)
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(bridgeSql).execute(bridgeParams)
                    )
                    .replaceWithVoid();

            songUnis.add(songUni);
        }

        if (songUnis.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Uni.join().all(songUnis).andFailFast().replaceWithVoid();
    }

    public Uni<Integer> deleteOlderThan(LocalDateTime cutoff) {
        String sql = "DELETE FROM " + TABLE_CONFIGURATIONS + " WHERE reg_date < $1";
        return client.preparedQuery(sql)
                .execute(Tuple.tuple().addOffsetDateTime(cutoff.atOffset(ZoneOffset.UTC)))
                .onItem().transform(rows -> rows.rowCount());
    }

    private int countSongs(LiveScene scene) {
        if (scene.getTimeline() == null) return 0;
        return scene.getTimeline().stream()
                .mapToInt(e -> e.getSongs() != null ? e.getSongs().size() : 0)
                .sum();
    }
}
