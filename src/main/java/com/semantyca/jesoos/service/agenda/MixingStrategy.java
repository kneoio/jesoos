package com.semantyca.jesoos.service.agenda;

import com.semantyca.mixpla.model.cnst.MixingType;

public record MixingStrategy(MixingType mergingType, int songsQuantity, boolean needsIntros) {
}
