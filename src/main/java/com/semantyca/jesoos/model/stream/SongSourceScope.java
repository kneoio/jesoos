package com.semantyca.jesoos.model.stream;

import java.util.UUID;

public sealed interface SongSourceScope {
    record BrandScope(UUID brandId) implements SongSourceScope {}
    record OwnerScope(long userId) implements SongSourceScope {}
}
