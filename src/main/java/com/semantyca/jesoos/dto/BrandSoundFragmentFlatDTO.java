package com.semantyca.jesoos.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.officeframe.dto.GenreDTO;
import com.semantyca.officeframe.dto.LabelDTO;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrandSoundFragmentFlatDTO {
    private UUID id;
    private UUID defaultBrandId;
    private int playedByBrandCount;
    private int ratedByBrandCount;
    private OffsetDateTime lastTimePlayedByBrand;
    private String title;
    private String artist;
    private String album;
    private SourceType source;
    private List<LabelDTO> labels;
    private List<GenreDTO> genres;
    private List<UUID> representedInBrands;
}
