package com.semantyca.djinn.model.stream;

import com.semantyca.mixpla.model.PlaylistRequest;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.ScenePrompt;
import com.semantyca.mixpla.model.cnst.GeneratedContentStatus;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.cnst.WayOfSourcing;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
public class LiveScene {
    private final UUID sceneId;
    private final String sceneTitle;
    private final LocalDateTime scheduledStartTime;
    private final int durationSeconds;
    private final double dayPercentage;
    private final List<PendingSongEntry> songs;
    private final LocalTime originalStartTime;
    private final LocalTime originalEndTime;
    private final WayOfSourcing sourcing;
    private final String playlistTitle;
    private final String artist;
    private final List<UUID> genres;
    private final List<UUID> labels;
    private final List<PlaylistItemType> playlistItemTypes;
    private final List<SourceType> sourceTypes;
    private final String searchTerm;
    private final List<UUID> soundFragments;
    private final List<ScenePrompt> contentPrompts;
    private final boolean oneTimeRun;
    private final double talkativity;
    private final List<ScenePrompt> introPrompts;
    @Setter
    private LocalDateTime sentToQueueAt;
    @Setter
    private GeneratedContentStatus generatedContentStatus;
    @Setter
    private LocalDateTime actualStartTime;
    @Setter
    private LocalDateTime actualEndTime;
    @Setter
    private LocalDateTime lastRunDate;

    public LiveScene(Scene scene, LocalDateTime scheduledStartTime) {
        this.sceneId = scene.getId();
        this.sceneTitle = scene.getTitle();
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = scene.getDurationSeconds();
        this.dayPercentage = this.durationSeconds / 86400.0;
        this.songs = new ArrayList<>();
        this.originalStartTime = (scene.getStartTime() != null && !scene.getStartTime().isEmpty()) ? scene.getStartTime().get(0) : null;
        this.originalEndTime = null;

        PlaylistRequest pr = scene.getPlaylistRequest();
        if (pr != null) {
            this.sourcing = pr.getSourcing();
            this.playlistTitle = pr.getTitle();
            this.artist = pr.getArtist();
            this.genres = pr.getGenres();
            this.labels = pr.getLabels();
            this.playlistItemTypes = pr.getType();
            this.sourceTypes = pr.getSource();
            this.searchTerm = pr.getSearchTerm();
            this.soundFragments = pr.getSoundFragments();
            this.contentPrompts = pr.getContentPrompts();
        } else {
            this.sourcing = null;
            this.playlistTitle = null;
            this.artist = null;
            this.genres = null;
            this.labels = null;
            this.playlistItemTypes = null;
            this.sourceTypes = null;
            this.searchTerm = null;
            this.soundFragments = null;
            this.contentPrompts = null;
        }

        this.generatedContentStatus = (this.sourcing == WayOfSourcing.GENERATED) ? GeneratedContentStatus.PENDING : null;
        this.oneTimeRun = scene.isOneTimeRun();
        this.talkativity = scene.getTalkativity();
        this.introPrompts = scene.getIntroPrompts();
    }

    public LiveScene(UUID sceneId, String sceneTitle, LocalDateTime scheduledStartTime, int durationSeconds,
                     LocalTime originalStartTime, LocalTime originalEndTime,
                     WayOfSourcing sourcing, String playlistTitle, String artist,
                     List<UUID> genres, List<UUID> labels, List<PlaylistItemType> playlistItemTypes,
                     List<SourceType> sourceTypes, String searchTerm, List<UUID> soundFragments,
                     List<ScenePrompt> contentPrompts, boolean oneTimeRun, double talkativity,
                     List<ScenePrompt> introPrompts) {
        this.sceneId = sceneId;
        this.sceneTitle = sceneTitle;
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = durationSeconds;
        this.dayPercentage = this.durationSeconds / 86400.0;
        this.songs = new ArrayList<>();
        this.originalStartTime = originalStartTime;
        this.originalEndTime = originalEndTime;
        this.sourcing = sourcing;
        this.playlistTitle = playlistTitle;
        this.artist = artist;
        this.genres = genres;
        this.labels = labels;
        this.playlistItemTypes = playlistItemTypes;
        this.sourceTypes = sourceTypes;
        this.searchTerm = searchTerm;
        this.soundFragments = soundFragments;
        this.contentPrompts = contentPrompts;
        this.oneTimeRun = oneTimeRun;
        this.talkativity = talkativity;
        this.introPrompts = introPrompts;
    }

    public void addSong(PendingSongEntry song) {
        this.songs.add(song);
    }

    public LocalDateTime getScheduledEndTime() {
        return scheduledStartTime.plusSeconds(durationSeconds);
    }

    public boolean isActiveAt(LocalTime time, LocalTime nextSceneStartTime) {
        if (originalStartTime == null) {
            return false;
        }
        
        LocalTime effectiveEndTime = originalEndTime;
        if (effectiveEndTime == null) {
            effectiveEndTime = nextSceneStartTime;
        }
        
        if (effectiveEndTime == null) {
            return !time.isBefore(originalStartTime);
        }
        
        if (effectiveEndTime.isAfter(originalStartTime)) {
            return !time.isBefore(originalStartTime) && time.isBefore(effectiveEndTime);
        } else {
            return !time.isBefore(originalStartTime) || time.isBefore(effectiveEndTime);
        }
    }
}
