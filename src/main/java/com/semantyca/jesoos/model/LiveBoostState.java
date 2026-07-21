package com.semantyca.jesoos.model;

import com.semantyca.mixpla.model.cnst.Boost;

import java.util.concurrent.atomic.AtomicInteger;

public record LiveBoostState(AtomicInteger remaining, Boost type) {
}
