package com.semantyca.djinn.dto.radiostation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.mixpla.model.cnst.SubmissionPolicy;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RadioStationStatusDTO {
    private String name;
    private String slugName;
    private String managedBy;
    private String djName;
    private String djPreferredLang;
    private String djStatus;
    private String currentStatus;
    private String countryCode;
    private String color;
    private String description;
    private long availableSongs;
    private SubmissionPolicy oneTimeStreamPolicy;
    private SubmissionPolicy submissionPolicy;
    private SubmissionPolicy messagingPolicy;
    private long bitRate;
    private double popularityRate;
}
