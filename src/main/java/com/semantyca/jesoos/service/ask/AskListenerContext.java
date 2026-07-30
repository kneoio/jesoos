package com.semantyca.jesoos.service.ask;

import com.semantyca.core.model.UserData;
import com.semantyca.jesoos.service.chat.tools.ListenerLabelCache;
import com.semantyca.jesoos.service.knowledge.Audience;
import com.semantyca.mixpla.model.Listener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds AskState listener/audience/label fields from a Listener row. */
final class AskListenerContext {

    record Snapshot(String listenerContext, String audiences, List<String> labels) {}

    private AskListenerContext() {}

    static Snapshot empty() {
        return new Snapshot("", Audience.USER.identifier(), List.of());
    }

    static Snapshot from(Listener listener, ListenerLabelCache labelCache) {
        if (listener == null) {
            return empty();
        }
        StringBuilder sb = new StringBuilder("[Listener profile:");
        UserData ud = listener.getUserData();
        if (ud != null && ud.getData() != null) {
            ud.getData().forEach((k, v) -> sb.append(" ").append(k).append("=").append(v).append(";"));
        }
        if (listener.getLocalizedName() != null && !listener.getLocalizedName().isEmpty()) {
            listener.getLocalizedName().forEach((lang, name) ->
                    sb.append(" localized_name(").append(lang).append(")=").append(name).append(";"));
        }
        if (listener.getNickName() != null && !listener.getNickName().isEmpty()) {
            listener.getNickName().forEach((lang, name) ->
                    sb.append(" nick_name(").append(lang).append(")=").append(name).append(";"));
        }
        List<String> resolvedLabels = labelCache.resolveToIdentifiers(listener.getLabels());
        if (!resolvedLabels.isEmpty()) {
            sb.append(" labels=").append(resolvedLabels).append(";");
        }
        Set<Audience> audiences = Audience.fromLabels(resolvedLabels);
        sb.append(" audience=").append(Audience.primary(audiences).identifier()).append(";");
        sb.append("]");
        return new Snapshot(sb.toString(), Audience.join(audiences), List.copyOf(resolvedLabels));
    }

    static Map<String, Object> toStateUpdates(Snapshot snapshot) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(AskState.LISTENER_CONTEXT, snapshot.listenerContext());
        updates.put(AskState.AUDIENCES, snapshot.audiences());
        updates.put(AskState.LABELS, snapshot.labels());
        return updates;
    }
}
