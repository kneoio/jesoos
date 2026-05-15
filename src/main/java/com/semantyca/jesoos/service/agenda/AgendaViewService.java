package com.semantyca.jesoos.service.agenda;

import com.semantyca.jesoos.dto.agenda.AgendaDTO;
import com.semantyca.jesoos.dto.agenda.SceneDTO;
import com.semantyca.jesoos.dto.agenda.TimelineEntryDTO;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.live.BrandPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class AgendaViewService {

    @Inject
    BrandPool brandPool;

    public AgendaDTO getAgendaByBrand(String brand) {
        return brandPool.getStationsSnapshot().stream()
                .filter(stream -> stream.getSlugName().equals(brand))
                .filter(stream -> stream.getAgenda() != null)
                .findFirst()
                .map(this::buildAgendaDTO)
                .orElse(null);
    }

    private AgendaDTO buildAgendaDTO(ILiveStream stream) {
        StreamAgenda agenda = stream.getAgenda();
        List<SceneDTO> sceneDTOs = agenda.getLiveScenes().stream()
                .map(this::buildSceneDTO)
                .collect(Collectors.toList());

        return AgendaDTO.builder()
                .key(stream.getSlugName())
                .timezone(stream.getTimeZone() != null ? stream.getTimeZone().getId() : null)
                .country(stream.getCountry() != null ? stream.getCountry().name() : null)
                .createdAt(agenda.getCreatedAt())
                .totalScenes(agenda.getLiveScenes().size())
                .scenes(sceneDTOs)
                .build();
    }

    private SceneDTO buildSceneDTO(LiveScene scene) {
        List<TimelineEntryDTO> timelineDTOs = null;
        if (scene.getTimeline() != null) {
            timelineDTOs = scene.getTimeline().stream()
                    .map(this::buildTimelineEntryDTO)
                    .collect(Collectors.toList());
        }

        LocalDateTime startTime = null;
        LocalDateTime endTime = null;
        if (timelineDTOs != null && !timelineDTOs.isEmpty()) {
            startTime = timelineDTOs.getFirst().getScheduledEmissionTime();
            TimelineEntryDTO lastEntry = timelineDTOs.getLast();
            endTime = lastEntry.getScheduledEmissionTime()
                    .plusSeconds(lastEntry.getDurationSeconds());
        }

        int totalSongs = scene.getTimeline() == null ? 0 :
                scene.getTimeline().stream().mapToInt(e -> e.getSongs().size()).sum();

        return SceneDTO.builder()
                .id(scene.getSceneId().toString())
                .title(scene.getSceneTitle())
                .firstEmissionTime(startTime)
                .lastEmissionTime(endTime)
                .durationSeconds(scene.getDurationSeconds())
                .totalSongs(totalSongs)
                .timelineBuilt(scene.isTimelineBuild())
                .fitSeconds(scene.getFitSeconds())
                .timeline(timelineDTOs)
                .build();
    }

    private TimelineEntryDTO buildTimelineEntryDTO(TimelineEntry entry) {
        List<TimelineEntryDTO.SongDTO> songDTOs = entry.getSongs().stream()
                .map(songEntry -> TimelineEntryDTO.SongDTO.builder()
                        .songId(songEntry.getSoundFragment().getId().toString())
                        .songTitle(songEntry.getSoundFragment().getTitle())
                        .artist(songEntry.getSoundFragment().getArtist())
                        .durationSeconds(songEntry.getDurationSeconds())
                        .language(
                                songEntry.getPromptEntry() != null &&
                                        songEntry.getPromptEntry().getLanguage() != null
                                        ? songEntry.getPromptEntry().getLanguage().name()
                                        : null
                        )
                        .shared(songEntry.isShared())
                        .sharerName(songEntry.getSharerName())
                        .build())
                .collect(Collectors.toList());


        List<TimelineEntryDTO.StatusRecordDTO> historyDTOs = entry.getStatusHistory().stream()
                .map(r -> TimelineEntryDTO.StatusRecordDTO.builder()
                        .status(r.status().name())
                        .at(r.at())
                        .build())
                .collect(Collectors.toList());

        return TimelineEntryDTO.builder()
                .id(entry.getId().toString())
                .sequenceNumber(entry.getSequenceNumber())
                .scheduledEmissionTime(entry.getScheduledEmissionTime())
                .songs(songDTOs)
                .durationSeconds(entry.getEstimatedDurationSeconds())
                .mixingStrategy(entry.getMixingStrategy().name())
                .hasIntro(entry.isHasIntro())
                .hasJingle(entry.isHasJingle())
                .generated(entry.isGenerated())
                .status(entry.getStatus().name())
                .statusHistory(historyDTOs)
                .build();
    }
}
