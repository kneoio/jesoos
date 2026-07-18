package com.semantyca.jesoos.model.stream;

import com.semantyca.mixpla.model.soundfragment.SoundFragment;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SongPool(List<SoundFragment> songs, Map<UUID, SharedMeta> sharedInfo) {

    public record SharedMeta(String sharerName, String contributorEmail, boolean priority) {}
}
