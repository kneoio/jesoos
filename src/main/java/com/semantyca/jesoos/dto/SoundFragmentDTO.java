package com.semantyca.jesoos.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.DurationDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.DurationSerializer;
import com.semantyca.core.dto.AbstractDTO;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SoundFragmentDTO extends AbstractDTO {
    private SourceType source;
    private Integer status = -1;
    @NotNull
    private PlaylistItemType type;
    @NotBlank
    private String title;
    @NotBlank
    private String artist;
    @NotNull
    @NotEmpty
    private List<UUID> genres;
    private List<UUID> labels;
    private String album;
    private String slugName;
    @JsonDeserialize(using = DurationDeserializer.class)
    @JsonSerialize(using = DurationSerializer.class)
    private Duration length;
    private String description;
    private List<String> brands;  //?
    private List<String> newlyUploaded;
    private List<UploadFileDTO> uploadedFiles;
    private List<UUID> representedInBrands;
    private LocalDateTime expiresAt;

    public SoundFragmentDTO(String id) {
        this.id = UUID.fromString(id);
    }
}