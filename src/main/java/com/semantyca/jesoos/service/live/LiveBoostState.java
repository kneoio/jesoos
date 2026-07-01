package com.semantyca.jesoos.service.live;

import com.semantyca.mixpla.model.cnst.Boost;

import java.util.concurrent.atomic.AtomicInteger;

record LiveBoostState(AtomicInteger remaining, Boost type) {
}
