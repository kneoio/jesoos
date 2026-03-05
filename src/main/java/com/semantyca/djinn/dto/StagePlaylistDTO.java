package com.semantyca.djinn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StagePlaylistDTO {
    private String sourcing;
    private String title;
    private String artist;
    private List<UUID> genres;
    private List<UUID> labels;
    private List<String> type;
    private List<String> source;
    private String searchTerm;
    private List<UUID> soundFragments;
    private List<ScenePromptDTO> prompts;
}
