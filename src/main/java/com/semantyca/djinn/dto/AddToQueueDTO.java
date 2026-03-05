package com.semantyca.djinn.dto;

import com.semantyca.djinn.service.manipulation.mixing.MergingType;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Setter
@Getter
public class AddToQueueDTO {
    private MergingType mergingMethod;
    private Map<String, String> filePaths;
    private Map<String, UUID> soundFragments;
    private Integer priority = 100;

    @Override
    public String toString() {
        return "mergingMethod=" + mergingMethod +
                ", filePaths=" + filePaths +
                ", priority=" + priority;
    }

}