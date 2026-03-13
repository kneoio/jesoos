package com.semantyca.jesoos.dto.radiostation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.dto.AbstractDTO;
import com.semantyca.core.dto.validation.ValidCountry;
import com.semantyca.core.dto.validation.ValidLocalizedName;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.cnst.SubmissionPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URL;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrandDTO extends AbstractDTO {
    @NotNull(message = "Localized name is required")
    @ValidLocalizedName(
            minLength = 1,
            maxLength = 255,
            allowEmptyMap = false,
            requireDefaultLanguage = true,
            defaultLanguage = LanguageCode.en,
            message = "Invalid localized name format"
    )
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    @NotNull(message = "Country is required")
    @NotBlank(message = "Country cannot be empty")
    @ValidCountry(message = "It is not available for the country")
    private String country;
    @NotNull
    private ManagedBy managedBy;
    private URL hlsUrl;
    private URL iceCastUrl;
    private URL mp3Url;
    private URL mixplaUrl;
    @NotBlank
    @Pattern(regexp = "^[A-Za-z]+/[A-Za-z_]+$", message = "Invalid timezone format")
    private String timeZone;
    private String color;
    private String description;
    private String titleFont;
    private long bitRate;
    private double popularityRate;
    private StreamStatus status = StreamStatus.OFF_LINE;
    private SubmissionPolicy oneTimeStreamPolicy = SubmissionPolicy.NOT_ALLOWED;
    private SubmissionPolicy submissionPolicy = SubmissionPolicy.NOT_ALLOWED;
    private SubmissionPolicy messagingPolicy = SubmissionPolicy.REVIEW_REQUIRED;
    private Integer isTemporary = 0;
    private UUID aiAgentId;
    private UUID profileId;
    private boolean aiOverridingEnabled;
    private boolean profileOverridingEnabled;
    private AiOverridingDTO aiOverriding;
    private ProfileOverridingDTO profileOverriding;
    private Integer archived;
    @NotNull
    @NotEmpty
    private List<BrandScriptEntryDTO> scripts;
    private OwnerDTO owner;

}