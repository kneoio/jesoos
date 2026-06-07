package com.semantyca.jesoos.service.agenda;

import com.semantyca.core.model.user.SuperUser;
import com.semantyca.jesoos.dto.agenda.AgendaDTO;
import com.semantyca.jesoos.dto.agenda.SceneDTO;
import com.semantyca.jesoos.dto.agenda.TimelineEntryDTO;
import com.semantyca.jesoos.model.stream.ILiveStream;
import com.semantyca.jesoos.model.stream.LiveScene;
import com.semantyca.jesoos.model.stream.PromptEntry;
import com.semantyca.jesoos.model.stream.SongEntry;
import com.semantyca.jesoos.model.stream.StreamAgenda;
import com.semantyca.jesoos.model.stream.TimelineEntry;
import com.semantyca.jesoos.service.PromptService;
import com.semantyca.jesoos.service.live.BrandPool;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class AgendaViewService {

    @Inject
    BrandPool brandPool;

    @Inject
    PromptService promptService;

    public AgendaDTO getAgendaByBrand(String brand) {
        return brandPool.getStationsSnapshot().stream()
                .filter(stream -> stream.getSlugName().equals(brand))
                .filter(stream -> stream.getAgenda() != null)
                .findFirst()
                .map(stream -> buildAgendaDTO(stream, Map.of()))
                .orElse(null);
    }

    public Uni<AgendaDTO> getAgendaByBrandAsync(String brand) {
        ILiveStream stream = brandPool.getStationsSnapshot().stream()
                .filter(s -> s.getSlugName().equals(brand))
                .filter(s -> s.getAgenda() != null)
                .findFirst()
                .orElse(null);

        if (stream == null) return Uni.createFrom().nullItem();

        Set<UUID> promptIds = stream.getAgenda().getLiveScenes().stream()
                .flatMap(scene -> scene.getTimeline() == null ? Stream.empty() : scene.getTimeline().stream())
                .flatMap(entry -> entry.getSongs().stream())
                .map(SongEntry::getPromptEntry)
                .filter(pe -> pe != null && pe.getPromptId() != null)
                .map(PromptEntry::getPromptId)
                .collect(Collectors.toSet());

        if (promptIds.isEmpty()) {
            return Uni.createFrom().item(buildAgendaDTO(stream, Map.of()));
        }

        return Multi.createFrom().iterable(promptIds)
                .onItem().transformToUniAndMerge(id ->
                        promptService.getById(id, SuperUser.build())
                                .map(p -> Map.entry(id, p.getTitle()))
                                .onFailure().recoverWithItem(Map.entry(id, ""))
                )
                .collect().asMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(titleMap -> buildAgendaDTO(stream, titleMap));
    }

    private AgendaDTO buildAgendaDTO(ILiveStream stream, Map<UUID, String> promptTitles) {
        StreamAgenda agenda = stream.getAgenda();
        List<SceneDTO> sceneDTOs = agenda.getLiveScenes().stream()
                .map(scene -> buildSceneDTO(scene, promptTitles))
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

    private SceneDTO buildSceneDTO(LiveScene scene, Map<UUID, String> promptTitles) {
        List<TimelineEntryDTO> timelineDTOs = null;
        if (scene.getTimeline() != null) {
            timelineDTOs = scene.getTimeline().stream()
                    .map(entry -> buildTimelineEntryDTO(entry, promptTitles))
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

    private TimelineEntryDTO buildTimelineEntryDTO(TimelineEntry entry, Map<UUID, String> promptTitles) {
        List<TimelineEntryDTO.SongDTO> songDTOs = entry.getSongs().stream()
                .map(songEntry -> {
                    PromptEntry pe = songEntry.getPromptEntry();
                    String promptTitle = null;
                    if (pe != null && pe.getPromptId() != null) {
                        promptTitle = promptTitles.get(pe.getPromptId());
                    }
                    return TimelineEntryDTO.SongDTO.builder()
                            .songId(songEntry.getSoundFragment().getId().toString())
                            .songTitle(songEntry.getSoundFragment().getTitle())
                            .artist(songEntry.getSoundFragment().getArtist())
                            .durationSeconds(songEntry.getDurationSeconds())
                            .language(pe != null && pe.getLanguage() != null ? pe.getLanguage().name() : null)
                            .promptTitle(promptTitle)
                            .shared(songEntry.isShared())
                            .sharerName(songEntry.getSharerName())
                            .build();
                })
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
