package com.semantyca.djinn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UploadFileDTO {
    private String id;
    private String name;
    private String status; // "pending"|"uploading"|"finished"|"removed"|"error"
    private String url;
    private Integer percentage;
    private String batchId;
    private String type;
    private String fullPath;
    private String thumbnailUrl;
    private AudioMetadataDTO metadata;
    private Long fileSize;
    private Long estimatedDurationSeconds;
    private String errorMessage;
}