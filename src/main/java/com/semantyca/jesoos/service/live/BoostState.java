package com.semantyca.jesoos.service.live;

import com.semantyca.mixpla.model.cnst.Boost;

import java.util.concurrent.atomic.AtomicInteger;

record BoostState(AtomicInteger remaining, Boost type) {
}
