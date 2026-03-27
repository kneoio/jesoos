package com.semantyca.jesoos.service.stream;

import com.semantyca.jesoos.dto.agenda.AgendaDTO;
import com.semantyca.jesoos.dto.agenda.AgendasResponseDTO;
import com.semantyca.jesoos.dto.agenda.SceneDTO;
import com.semantyca.jesoos.dto.agenda.TimelineEntryDTO;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class AgendaViewService {

    @Inject
    BrandPool brandPool;

    public AgendasResponseDTO getAllAgendas() {
        AgendasResponseDTO response = new AgendasResponseDTO();
        
        brandPool.getOnlineStationsSnapshot().forEach(stream -> {
            if (stream.getAgenda() != null) {
                String key = stream.getSlugName();
                StreamAgenda agenda = stream.getAgenda();
                AgendaDTO agendaDTO = buildAgendaDTO(key, agenda);
                response.addAgenda(key, agendaDTO);
            }
        });
        
        return response;
    }

    private AgendaDTO buildAgendaDTO(String key, StreamAgenda agenda) {
        List<SceneDTO> sceneDTOs = agenda.getLiveScenes().stream()
                .map(this::buildSceneDTO)
                .collect(Collectors.toList());
        
        return AgendaDTO.builder()
                .key(key)
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
        
        return SceneDTO.builder()
                .id(scene.getSceneId().toString())
                .title(scene.getSceneTitle())
                .scheduledStartTime(scene.getScheduledStartTime())
                .scheduledEndTime(scene.getScheduledEndTime())
                .durationSeconds(scene.getDurationSeconds())
                .totalSongs(scene.getSongs().size())
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
                .batchId(entry.getBatchId())
                .status(entry.getStatus().name())
                .build();
    }
}
