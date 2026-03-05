package com.semantyca.djinn.model.stream;


import com.semantyca.mixpla.model.cnst.StreamStatus;

import java.time.LocalDateTime;

public record StatusChangeRecord(LocalDateTime timestamp, StreamStatus oldStatus,
                                 StreamStatus newStatus) {
}
