package com.semantyca.jesoos.model.stream;

import com.semantyca.jesoos.service.agenda.TriggerContext;
import com.semantyca.mixpla.model.CustomAction;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.ContentStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class LiveScene {
    private UUID sceneId;
    private String sceneTitle;
    private LocalTime originalStartTime;  //the value from Scene document
    private LocalDateTime actualStartTime; //the actual time when the scene started emit TimelineEntries
    private LocalDateTime actualEndTime; //the actual time when the scene stopped emit TimelineEntries
    private ZoneId timeZone;
    private UUID agentId;
    private ContentStatus contentStatus;
    private List<ScenePrompt> contentPrompts;
    private List<ScenePrompt> introPrompts;
    private List<CustomAction> actions;
    private TriggerContext triggerContext;
    private UUID traceId;
    private List<TimelineEntry> timeline;
    private boolean timelineBuild;
    private boolean oneTimeRun;
    private int fitSeconds;
    private String otsSlugName;

    public LocalDateTime getEndTime() {
        if (timeline == null || timeline.isEmpty()) return null;
        TimelineEntry last = timeline.getLast();
        return last.getScheduledEmissionTime()
                .plusSeconds(last.getEstimatedDurationSeconds());
    }

    public int getDurationSeconds() {
        if (timeline == null || timeline.isEmpty()) return 0;
        return timeline.stream().mapToInt(TimelineEntry::getEstimatedDurationSeconds).sum();
    }

    public boolean isFinished() {
        if (timeline == null || timeline.isEmpty()) return false;
        return timeline.stream().allMatch(e ->
                e.getStatus() == TimelineEntryStatus.COMPLETED ||
                e.getStatus() == TimelineEntryStatus.FAILED ||
                e.getStatus() == TimelineEntryStatus.SKIPPED);
    }

    public boolean isActiveAt(LocalTime time, LocalTime nextSceneStartTime) {
        if (originalStartTime == null) {
            return false;
        }
        
        if (nextSceneStartTime == null) {
            return !time.isBefore(originalStartTime);
        }
        
        if (nextSceneStartTime.isAfter(originalStartTime)) {
            return !time.isBefore(originalStartTime) && time.isBefore(nextSceneStartTime);
        } else {
            return !time.isBefore(originalStartTime) || time.isBefore(nextSceneStartTime);
        }
    }

}
