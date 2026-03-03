package com.semantyca.djinn.model.soundfragment;

import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class SoundFragmentFilter {
    private boolean activated = false;
    private List<UUID> genre;
    private List<UUID> labels;
    private List<SourceType> source;
    private List<PlaylistItemType> type;
    private String searchTerm;

    public boolean isActivated() {
        if (activated) {
            return true;
        }
        return hasAnyFilter();
    }

    private boolean hasAnyFilter() {
        if (genre != null && !genre.isEmpty()) {
            return true;
        }
        if (labels != null && !labels.isEmpty()) {
            return true;
        }
        if (source != null && !source.isEmpty()) {
            return true;
        }
        if (type != null && !type.isEmpty()) {
            return true;
        }
        return searchTerm != null && !searchTerm.trim().isEmpty();
    }
}