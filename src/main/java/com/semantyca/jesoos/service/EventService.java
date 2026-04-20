package com.semantyca.jesoos.service;

import com.semantyca.core.dto.scheduler.OnceTriggerDTO;
import com.semantyca.core.dto.scheduler.PeriodicTriggerDTO;
import com.semantyca.core.dto.scheduler.ScheduleDTO;
import com.semantyca.core.model.cnst.TriggerType;
import com.semantyca.core.model.scheduler.OnceTrigger;
import com.semantyca.core.model.scheduler.PeriodicTrigger;
import com.semantyca.core.model.scheduler.Scheduler;
import com.semantyca.core.model.scheduler.Task;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.jesoos.dto.ScenePromptDTO;
import com.semantyca.jesoos.dto.StagePlaylistDTO;
import com.semantyca.jesoos.dto.event.EventDTO;
import com.semantyca.jesoos.repository.EventRepository;
import com.semantyca.mixpla.model.Event;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.EventPriority;
import com.semantyca.mixpla.model.cnst.EventType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class EventService extends AbstractService<Event, EventDTO> {
    private static final Logger LOGGER = Logger.getLogger(EventService.class);
    private final EventRepository repository;

    @Inject
    public EventService(UserService userService,
                        EventRepository repository
    ) {
        super(userService);
        this.repository = repository;
    }


    public Uni<List<Event>> findForBrand(String brandSlugName, int limit, int offset, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandSlugName, limit, offset, user, false);
    }

    public Uni<Event> upsert(String id, EventDTO dto, IUser user) {
        assert repository != null;

        Event entity = buildEntity(dto);

        Uni<Event> saveOperation;
        if (id == null) {
            saveOperation = repository.insert(entity, user);
        } else {
            saveOperation = repository.update(UUID.fromString(id), entity, user);
        }

        return saveOperation;
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }


    private String normalizeTimeString(String timeString) {
        if ("24:00".equals(timeString)) {
            return "00:00";
        }
        return timeString;
    }

    private Event buildEntity(EventDTO dto) {
        Event doc = new Event();
        doc.setBrandId(UUID.fromString(dto.getBrandId()));
        doc.setType(EventType.valueOf(dto.getType()));
        doc.setDescription(dto.getDescription());
        doc.setPriority(EventPriority.valueOf(dto.getPriority()));
        doc.setTimeZone(ZoneId.of(dto.getTimeZone()));
        if (dto.getSchedule() != null) {
            Scheduler schedule = new Scheduler();
            ScheduleDTO scheduleDTO = dto.getSchedule();
            schedule.setTimeZone(doc.getTimeZone());
            schedule.setEnabled(scheduleDTO.isEnabled());
            if (scheduleDTO.getTasks() != null && !scheduleDTO.getTasks().isEmpty()) {
                List<Task> tasks = scheduleDTO.getTasks().stream().map(taskDTO -> {
                    Task task = new Task();
                    task.setId(UUID.randomUUID());
                    task.setTriggerType(taskDTO.getTriggerType());

                    if (taskDTO.getTriggerType() == TriggerType.ONCE) {
                        OnceTrigger onceTrigger = new OnceTrigger();
                        OnceTriggerDTO onceTriggerDTO = taskDTO.getOnceTrigger();
                        onceTrigger.setStartTime(normalizeTimeString(onceTriggerDTO.getStartTime()));
                        onceTrigger.setDuration(onceTriggerDTO.getDuration());
                        onceTrigger.setWeekdays(onceTriggerDTO.getWeekdays());
                        task.setOnceTrigger(onceTrigger);
                    }

                    if (taskDTO.getTriggerType() == TriggerType.PERIODIC) {
                        PeriodicTrigger periodicTrigger = new PeriodicTrigger();
                        PeriodicTriggerDTO periodicTriggerDTO = taskDTO.getPeriodicTrigger();
                        periodicTrigger.setStartTime(normalizeTimeString(periodicTriggerDTO.getStartTime()));
                        periodicTrigger.setEndTime(normalizeTimeString(periodicTriggerDTO.getEndTime()));
                        periodicTrigger.setInterval(periodicTriggerDTO.getInterval());
                        periodicTrigger.setWeekdays(periodicTriggerDTO.getWeekdays());
                        task.setPeriodicTrigger(periodicTrigger);
                    }

                    return task;
                }).collect(Collectors.toList());
                schedule.setTasks(tasks);
            }
            doc.setScheduler(schedule);
        }
        doc.setScenePrompts(mapDTOsToActions(dto.getActions()));
        doc.setPlaylistRequest(mapDTOToStagePlaylist(dto.getStagePlaylist()));

        return doc;
    }

    private List<ScenePrompt> mapDTOsToActions(List<ScenePromptDTO> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(dto -> {
                    ScenePrompt scenePrompt = new ScenePrompt();
                    scenePrompt.setPromptId(dto.getPromptId());
                    scenePrompt.setRank(dto.getRank());
                    scenePrompt.setWeight(dto.getWeight());
                    scenePrompt.setActive(dto.isActive());
                    return scenePrompt;
                })
                .collect(Collectors.toList());
    }

    private PlaylistRequest mapDTOToStagePlaylist(StagePlaylistDTO dto) {
        if (dto == null) {
            return null;
        }
        PlaylistRequest playlistRequest = new PlaylistRequest();
        playlistRequest.setSourcing(dto.getSourcing() != null ? WayOfSourcing.valueOf(dto.getSourcing()) : null);
        playlistRequest.setTitle(dto.getTitle());
        playlistRequest.setArtist(dto.getArtist());
        playlistRequest.setGenres(dto.getGenres());
        playlistRequest.setLabels(dto.getLabels());
        playlistRequest.setType(dto.getType() != null ? dto.getType().stream().map(PlaylistItemType::valueOf).toList() : null);
        playlistRequest.setSource(dto.getSource() != null ? dto.getSource().stream().map(SourceType::valueOf).toList() : null);
        playlistRequest.setSearchTerm(dto.getSearchTerm());
        playlistRequest.setSoundFragments(dto.getSoundFragments());
        return playlistRequest;
    }
}
