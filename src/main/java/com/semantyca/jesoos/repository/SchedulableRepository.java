package com.semantyca.jesoos.repository;


import com.semantyca.core.model.scheduler.Schedulable;
import io.smallrye.mutiny.Uni;

import java.util.List;

public interface SchedulableRepository<T extends Schedulable> {
    Uni<List<T>> findActiveScheduled();
}