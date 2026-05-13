package com.semantyca.jesoos.model.stream;

import com.semantyca.mixpla.model.brand.Owner;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;

public record SharedSongEntry(SoundFragment soundFragment, Owner sharedBy) {}
