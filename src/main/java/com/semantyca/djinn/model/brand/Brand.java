package com.semantyca.djinn.model.brand;

import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.cnst.SubmissionPolicy;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import io.kneo.officeframe.cnst.CountryCode;
import io.kneo.officeframe.model.Label;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Brand extends SecureDataEntity<UUID> {

    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private ZoneId timeZone;
    private Integer archived;
    private Integer isTemporary = 0;
    private CountryCode country;
    private long bitRate;
    private ManagedBy managedBy = ManagedBy.ITSELF;
    private String color;
    private String description;
    private String titleFont;
    private double popularityRate;
    private UUID aiAgentId;
    private UUID profileId;
    private AiOverriding aiOverriding;
    private ProfileOverriding profileOverriding;
    private SubmissionPolicy oneTimeStreamPolicy = SubmissionPolicy.NOT_ALLOWED;
    private SubmissionPolicy submissionPolicy = SubmissionPolicy.NOT_ALLOWED;
    private SubmissionPolicy messagingPolicy = SubmissionPolicy.REVIEW_REQUIRED;
    private List<Label> labelList;
    private List<BrandScriptEntry> scripts;
    private Owner owner;

    public String toString() {
        return String.format("id: %s, slug: %s", getId(), slugName);
    }

}