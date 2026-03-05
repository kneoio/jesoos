package com.semantyca.djinn.dto;

import com.semantyca.djinn.IFilterDTO;
import com.semantyca.mixpla.model.cnst.SceneTimingMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class SceneFilterDTO implements IFilterDTO {
    private boolean activated = false;

    private SceneTimingMode timingMode;
    private UUID scriptId;


    @Override
    public boolean isActivated() {
        return activated || hasAnyFilter();
    }

    @Override
    public boolean hasAnyFilter() {
        return timingMode != null || scriptId != null;
    }
}