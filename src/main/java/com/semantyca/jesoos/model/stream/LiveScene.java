package com.semantyca.jesoos.model.stream;

import com.semantyca.jesoos.service.stream.TriggerContext;
import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.GeneratedContentStatus;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private GeneratedContentStatus generatedContentStatus;
    private TriggerContext triggerContext;
    private UUID traceId;
    private List<TimelineEntry> timeline;
    private boolean timelineBuild;

    public LocalDateTime getStartTime() {
        if (timeline == null || timeline.isEmpty()) return null;
        return timeline.getFirst().getScheduledEmissionTime();
    }

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
