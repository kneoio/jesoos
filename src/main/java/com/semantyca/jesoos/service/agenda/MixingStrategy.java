package com.semantyca.jesoos.service.agenda;

import com.semantyca.mixpla.model.cnst.MergingType;

public record MixingStrategy(MergingType mergingType, int songsQuantity, boolean needsIntros) {
}
