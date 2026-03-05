package com.semantyca.djinn.dto;

import com.semantyca.djinn.IFilterDTO;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class SoundFragmentFilterDTO implements IFilterDTO {
    private boolean activated = false;

    @NotEmpty(message = "Genres list cannot be empty when provided")
    private List<UUID> genres;

    @NotEmpty(message = "Labels list cannot be empty when provided")
    private List<UUID> labels;

    @NotEmpty(message = "Sources list cannot be empty when provided")
    private List<SourceType> sources;

    @NotEmpty(message = "Types list cannot be empty when provided")
    private List<PlaylistItemType> types;

    private String searchTerm;

    @Override
    public boolean isActivated() {
        return activated || hasAnyFilter();
    }

    @Override
    public boolean hasAnyFilter() {
        return (genres != null && !genres.isEmpty()) ||
               (labels != null && !labels.isEmpty()) ||
               (sources != null && !sources.isEmpty()) ||
               (types != null && !types.isEmpty()) ||
               (searchTerm != null && !searchTerm.trim().isEmpty());
    }
}