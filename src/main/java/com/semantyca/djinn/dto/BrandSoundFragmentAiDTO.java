package com.semantyca.djinn.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
public class BrandSoundFragmentAiDTO {
    private UUID id;
    private String title;
    private String artist;
    private List<String> genres;
    private List<String> labels;
    private String album;
    private String description;
    private int playedByBrandCount;
    private LocalDateTime lastTimePlayedByBrand;
}