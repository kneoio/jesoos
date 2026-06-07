package com.semantyca.jesoos.service.live;

import com.semantyca.jesoos.model.cnst.BoostType;

import java.util.concurrent.atomic.AtomicInteger;

record BoostState(AtomicInteger remaining, BoostType type) {
}
