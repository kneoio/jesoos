package com.semantyca.jesoos.service.live.scripting;

import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.jesoos.dto.BrandListenerDTO;
import com.semantyca.jesoos.service.chat.tools.ListenerLabelCache;

import java.util.List;
import java.util.UUID;

public final class ListenersHelper {

    private final List<BrandListenerDTO> listeners;
    private final ListenerLabelCache labelCache;
    private final LanguageCode lang;

    public ListenersHelper(List<BrandListenerDTO> listeners, ListenerLabelCache labelCache, LanguageCode lang) {
        this.listeners = listeners;
        this.labelCache = labelCache;
        this.lang = lang;
    }

    public List<String> all() {
        return resolveNames(listeners);
    }

    public List<String> byLabel(String identifier) {
        UUID labelId = labelCache.get(identifier);
        if (labelId == null) return List.of();
        return resolveNames(listeners.stream()
                .filter(dto -> dto.getListenerDTO() != null
                        && dto.getListenerDTO().getLabels() != null
                        && dto.getListenerDTO().getLabels().contains(labelId))
                .toList());
    }

    private List<String> resolveNames(List<BrandListenerDTO> source) {
        return source.stream()
                .map(dto -> {
                    var listener = dto.getListenerDTO();
                    if (listener == null) return null;
                    String name = listener.getLocalizedName() != null ? listener.getLocalizedName().get(lang) : null;
                    if (name == null || name.isBlank()) {
                        var userData = listener.getUserData();
                        if (userData != null) name = userData.get("preferred_name");
                    }
                    if (name == null || name.isBlank()) name = listener.getSlugName();
                    return name;
                })
                .filter(n -> n != null && !n.isBlank())
                .toList();
    }
}
